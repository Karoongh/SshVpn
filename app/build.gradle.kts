plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.sshvpn"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.example.sshvpn"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.3"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    // Fixed key so every CI build can update over the previous install.
    // Credentials documented in keystore/SIGNING.md
    signingConfigs {
        create("karoon") {
            val ks = rootProject.file("keystore/karoon-debug.jks")
            storeFile = ks
            storePassword = "karoon_tunnel_debug"
            keyAlias = "karoon"
            keyPassword = "karoon_tunnel_debug"
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("karoon")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("karoon")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.github.mwiede:jsch:0.2.20")
}
