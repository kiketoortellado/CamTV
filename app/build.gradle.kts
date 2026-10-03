plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "py.camtv"
    compileSdk = 34

    defaultConfig {
        applicationId = "py.camtv"
        minSdk = 22          // Fire OS 5 en adelante
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk {
            // Los Fire TV Stick son ARM (32 y 64 bits)
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // Firma fija para que las actualizaciones se instalen encima sin desinstalar.
    signingConfigs {
        create("camtv") {
            storeFile = file("camtv.keystore")
            storePassword = "camtv123"
            keyAlias = "camtv"
            keyPassword = "camtv123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("camtv")
        }
        debug {
            signingConfig = signingConfigs.getByName("camtv")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("org.videolan.android:libvlc-all:3.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
