import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePath: String? = System.getenv("SEBVIEWER_KEYSTORE")
val hasReleaseKeystore = keystorePath != null && File(keystorePath).exists()

android {
    namespace = "com.sebparoc.sebviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sebparoc.sebviewer"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("sebviewerVersionCode") as String).toInt()
        versionName = project.findProperty("sebviewerVersionName") as String
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = File(keystorePath!!)
                storePassword = System.getenv("SEBVIEWER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SEBVIEWER_KEY_ALIAS") ?: "sebviewer"
                keyPassword = System.getenv("SEBVIEWER_KEY_PASSWORD")
                    ?: System.getenv("SEBVIEWER_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
