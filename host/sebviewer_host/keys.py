"""Key name tables shared by the input backends.

The Android client sends X11 keysym *names* (e.g. "Return", "Control_L", "a").
Each backend converts those to whatever it needs.
"""

# name -> X11 keysym value
KEYSYMS = {
    "space": 0x20,
    "Return": 0xFF0D,
    "BackSpace": 0xFF08,
    "Tab": 0xFF09,
    "Escape": 0xFF1B,
    "Delete": 0xFFFF,
    "Insert": 0xFF63,
    "Home": 0xFF50,
    "End": 0xFF57,
    "Page_Up": 0xFF55,
    "Page_Down": 0xFF56,
    "Left": 0xFF51,
    "Up": 0xFF52,
    "Right": 0xFF53,
    "Down": 0xFF54,
    "Shift_L": 0xFFE1,
    "Control_L": 0xFFE3,
    "Alt_L": 0xFFE9,
    "Super_L": 0xFFEB,
    "Caps_Lock": 0xFFE5,
    "Print": 0xFF61,
    "Menu": 0xFF67,
    "XF86AudioLowerVolume": 0x1008FF11,
    "XF86AudioMute": 0x1008FF12,
    "XF86AudioRaiseVolume": 0x1008FF13,
    "XF86AudioPlay": 0x1008FF14,
    "XF86AudioPrev": 0x1008FF16,
    "XF86AudioNext": 0x1008FF17,
}
for _i in range(1, 13):
    KEYSYMS[f"F{_i}"] = 0xFFBE + _i - 1


def keysym_for_name(name: str):
    """Return the X11 keysym for a key name, or None if unknown."""
    if name in KEYSYMS:
        return KEYSYMS[name]
    if len(name) == 1:
        return keysym_for_char(name)
    return None


def keysym_for_char(ch: str) -> int:
    cp = ord(ch)
    if ch == "\n":
        return KEYSYMS["Return"]
    if ch == "\t":
        return KEYSYMS["Tab"]
    if 0x20 <= cp <= 0xFF:
        return cp
    return 0x01000000 | cp
