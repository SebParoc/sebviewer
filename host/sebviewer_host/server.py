"""WebSocket server: streams JPEG frames to clients and applies their input.

Protocol (JSON text frames unless noted):
  client -> host
    {"t":"auth","pin":"123456","name":"My phone"}
    {"t":"ack"}                              one per received video frame
    {"t":"move","x":0.4,"y":0.3}             normalised 0..1
    {"t":"btn","b":1,"d":true,"x":..,"y":..} button 1/2/3 down/up (x,y optional)
    {"t":"click","b":1,"x":..,"y":..,"n":2}  n clicks (default 1)
    {"t":"scroll","dx":0,"dy":1}             steps, +dy = down
    {"t":"key","k":"Return","d":true}        d omitted = press and release
    {"t":"text","s":"hello"}
    {"t":"cfg","fps":20,"q":60,"w":1280}
    {"t":"ping","ts":123}                    answered with {"t":"pong","ts":123}
  host -> client
    {"t":"hello","w":W,"h":H,"name":"host","version":"x"}
    {"t":"err","msg":"..."}
    {"t":"size","w":W,"h":H}
    binary frame: JPEG image
"""

from __future__ import annotations

import asyncio
import hmac
import io
import json
import logging
import queue
import socket
import threading
import time
from dataclasses import dataclass, field

from PIL import Image

from . import __version__
from .backends.base import Backend

log = logging.getLogger(__name__)

MAX_UNACKED = 2
_MOVE = object()  # queue marker: apply the latest pending pointer move
MAX_FAILURES = 5
LOCKOUT_SECONDS = 30


@dataclass
class StreamSettings:
    fps: float = 20.0
    quality: int = 60
    max_width: int = 1280


@dataclass
class Client:
    ws: object
    name: str = "?"
    last_seq: int = 0
    unacked: int = 0
    ack_event: asyncio.Event = field(default_factory=asyncio.Event)
    held_buttons: set = field(default_factory=set)
    held_keys: set = field(default_factory=set)
    pong: str | None = None


class Server:
    def __init__(self, backend: Backend, pin: str | None, host: str, port: int,
                 settings: StreamSettings):
        self.backend = backend
        self.pin = pin  # None: accept every client without a PIN
        self.host = host
        self.port = port
        self.settings = settings
        self.clients: dict[object, Client] = {}
        self.seq = 0
        self.frame: bytes = b""
        self.frame_size = (0, 0)
        self.cond: asyncio.Condition | None = None
        self.capture_task: asyncio.Task | None = None
        self._failures: dict[str, list[float]] = {}
        self._backend_lock = asyncio.Lock()
        # Input is applied by one worker thread, strictly in order: the portal
        # calls are synchronous and must never overlap or reorder.
        self._input_q: queue.SimpleQueue = queue.SimpleQueue()
        self._pending_move: tuple[float, float] | None = None
        self._move_lock = threading.Lock()
        threading.Thread(target=self._input_worker, name="input", daemon=True).start()

    # ------------------------------------------------------------- lifecycle
    async def serve(self) -> None:
        try:
            from websockets.asyncio.server import serve
        except ImportError:  # websockets < 13
            from websockets import serve  # type: ignore
        self.cond = asyncio.Condition()
        async with serve(self._handle, self.host, self.port, compression=None,
                         max_size=1 << 20, ping_interval=20, ping_timeout=20):
            log.info("Listening on %s:%s", self.host, self.port)
            await asyncio.Future()

    async def _ensure_backend(self) -> None:
        async with self._backend_lock:
            if not self.backend.alive:
                log.info("Starting screen capture")
                await asyncio.get_running_loop().run_in_executor(None, self._restart_backend)

    def _restart_backend(self) -> None:
        try:
            self.backend.stop()
        except Exception:  # noqa: BLE001
            pass
        self.backend.start()

    # --------------------------------------------------------------- capture
    async def _capture_loop(self) -> None:
        loop = asyncio.get_running_loop()
        last_raw = None
        log.info("Capture loop started")
        try:
            while self.clients:
                t0 = time.monotonic()
                try:
                    encoded = await loop.run_in_executor(None, self._grab_and_encode, last_raw)
                except Exception as exc:  # noqa: BLE001
                    log.exception("capture failed: %s", exc)
                    encoded = None
                if encoded is not None:
                    jpeg, last_raw, size = encoded
                    async with self.cond:
                        self.frame = jpeg
                        self.frame_size = size
                        self.seq += 1
                        self.cond.notify_all()
                delay = 1.0 / max(1.0, self.settings.fps) - (time.monotonic() - t0)
                if delay > 0:
                    await asyncio.sleep(delay)
        finally:
            log.info("Capture loop stopped")

    def _grab_and_encode(self, last_raw):
        img = self.backend.grab()
        if img is None:
            return None
        w, h = img.size
        maxw = self.settings.max_width
        if maxw and w > maxw:
            nh = max(1, round(h * maxw / w))
            img = img.resize((maxw, nh), Image.Resampling.BILINEAR)
        raw = img.tobytes()
        if raw == last_raw:
            return None
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=self.settings.quality)
        return buf.getvalue(), raw, (self.backend.width, self.backend.height)

    # --------------------------------------------------------------- clients
    def _remote(self, ws) -> str:
        try:
            return ws.remote_address[0]
        except Exception:  # noqa: BLE001
            return "?"

    def _locked_out(self, ip: str) -> bool:
        now = time.monotonic()
        fails = [t for t in self._failures.get(ip, []) if now - t < LOCKOUT_SECONDS]
        self._failures[ip] = fails
        return len(fails) >= MAX_FAILURES

    async def _handle(self, ws) -> None:
        ip = self._remote(ws)
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=10)
            msg = json.loads(raw)
        except Exception:  # noqa: BLE001
            await ws.close()
            return
        if msg.get("t") != "auth":
            await ws.close()
            return
        if self.pin is not None and self._locked_out(ip):
            await ws.send(json.dumps({"t": "err", "msg": "too many attempts, wait 30 s"}))
            await ws.close()
            return
        if self.pin is not None and not hmac.compare_digest(str(msg.get("pin", "")), self.pin):
            self._failures.setdefault(ip, []).append(time.monotonic())
            log.warning("Rejected connection from %s (bad PIN)", ip)
            await ws.send(json.dumps({"t": "err", "msg": "wrong PIN"}))
            await ws.close()
            return

        try:
            await self._ensure_backend()
        except Exception as exc:  # noqa: BLE001
            log.exception("could not start capture")
            await ws.send(json.dumps({"t": "err", "msg": f"screen capture failed: {exc}"}))
            await ws.close()
            return

        client = Client(ws=ws, name=str(msg.get("name", "?"))[:40])
        self.clients[ws] = client
        log.info("%s connected from %s", client.name, ip)
        await ws.send(json.dumps({
            "t": "hello", "w": self.backend.width, "h": self.backend.height,
            "name": socket.gethostname(), "version": __version__,
        }))
        if self.capture_task is None or self.capture_task.done():
            self.capture_task = asyncio.create_task(self._capture_loop())

        sender = asyncio.create_task(self._send_frames(client))
        try:
            async for raw in ws:
                if isinstance(raw, (bytes, bytearray)):
                    continue
                try:
                    self._on_message(client, json.loads(raw))
                except Exception as exc:  # noqa: BLE001
                    log.debug("bad message %r: %s", raw[:80], exc)
                if client.pong is not None:
                    pong, client.pong = client.pong, None
                    await ws.send(pong)
        except Exception as exc:  # noqa: BLE001
            log.debug("connection error: %s", exc)
        finally:
            sender.cancel()
            self.clients.pop(ws, None)
            self._release_held(client)
            log.info("%s disconnected", client.name)

    def _release_held(self, client: Client) -> None:
        for b in list(client.held_buttons):
            self._safe(self.backend.pointer_button, b, False)
        for k in list(client.held_keys):
            self._safe(self.backend.key, k, False)
        if not self.clients:
            self._safe(self.backend.release_all)

    async def _send_frames(self, client: Client) -> None:
        ws = client.ws
        sent_size = (0, 0)
        while True:
            async with self.cond:
                await self.cond.wait_for(lambda: self.seq > client.last_seq)
                frame, seq, size = self.frame, self.seq, self.frame_size
            if client.unacked >= MAX_UNACKED:
                client.ack_event.clear()
                try:
                    await asyncio.wait_for(client.ack_event.wait(), timeout=3)
                except asyncio.TimeoutError:
                    client.unacked = 0
            if size != sent_size:
                await ws.send(json.dumps({"t": "size", "w": size[0], "h": size[1]}))
                sent_size = size
            await ws.send(frame)
            client.last_seq = seq
            client.unacked += 1

    # ----------------------------------------------------------------- input
    def _input_worker(self) -> None:
        while True:
            item = self._input_q.get()
            if item is _MOVE:
                with self._move_lock:
                    move, self._pending_move = self._pending_move, None
                if move is None:
                    continue
                fn, args = self.backend.pointer_move, move
            else:
                fn, args = item
            try:
                fn(*args)
            except Exception as exc:  # noqa: BLE001
                log.warning("input failed: %s", exc)

    def _safe(self, fn, *args) -> None:
        self._input_q.put((fn, args))

    def _move(self, x: float, y: float) -> None:
        """Queue a pointer move; only the newest one is applied when we fall behind."""
        with self._move_lock:
            first = self._pending_move is None
            self._pending_move = (x, y)
        if first:
            self._input_q.put(_MOVE)

    def _px(self, msg: dict):
        if "x" not in msg or "y" not in msg:
            return None
        x = min(max(float(msg["x"]), 0.0), 1.0) * max(1, self.backend.width - 1)
        y = min(max(float(msg["y"]), 0.0), 1.0) * max(1, self.backend.height - 1)
        return x, y

    def _on_message(self, client: Client, msg: dict) -> None:
        t = msg.get("t")
        b = self.backend
        if t == "ack":
            client.unacked = max(0, client.unacked - 1)
            client.ack_event.set()
        elif t == "ping":
            client.pong = json.dumps({"t": "pong", "ts": msg.get("ts")})
        elif t == "move":
            p = self._px(msg)
            if p:
                self._move(*p)
        elif t == "btn":
            p = self._px(msg)
            if p:
                self._safe(b.pointer_move, *p)
            button, down = int(msg.get("b", 1)), bool(msg.get("d", True))
            self._safe(b.pointer_button, button, down)
            (client.held_buttons.add if down else client.held_buttons.discard)(button)
        elif t == "click":
            p = self._px(msg)
            if p:
                self._safe(b.pointer_move, *p)
            button = int(msg.get("b", 1))
            for _ in range(max(1, min(3, int(msg.get("n", 1))))):
                self._safe(b.pointer_button, button, True)
                self._safe(b.pointer_button, button, False)
        elif t == "scroll":
            self._safe(b.scroll, int(msg.get("dx", 0)), int(msg.get("dy", 0)))
        elif t == "key":
            name = str(msg.get("k", ""))
            if not name:
                return
            if "d" in msg:
                down = bool(msg["d"])
                self._safe(b.key, name, down)
                (client.held_keys.add if down else client.held_keys.discard)(name)
            else:
                self._safe(b.key, name, True)
                self._safe(b.key, name, False)
        elif t == "text":
            s = str(msg.get("s", ""))
            if s:
                self._safe(b.type_text, s)
        elif t == "cfg":
            if "fps" in msg:
                self.settings.fps = min(60.0, max(1.0, float(msg["fps"])))
            if "q" in msg:
                self.settings.quality = min(95, max(10, int(msg["q"])))
            if "w" in msg:
                self.settings.max_width = min(4096, max(320, int(msg["w"])))
            log.info("stream settings: %s", self.settings)
