plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.example.gemmakey"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.gemmakey"
        minSdk = 30          // AccessibilityService.takeScreenshot() requires API 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/{AL2.0,LGPL2.1}"
            )
        }
        jniLibs {
            // Keep native libs for MediaPipe and LiteRT
            keepDebugSymbols += "**/*.so"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")  // Primary target: modern ARM devices with NPU
            isUniversalApk = false
        }
    }
}

dependencies {
    // ── On-device LLM: Google AI Edge AICore (Gemini Nano, Pixel 8+) ──────────
    // Experimental — only initialises on AICore-capable devices
    implementation("com.google.android.ai.edge.aicore:aicore:0.0.1-exp03")

    // ── On-device LLM: LiteRT-LM official Kotlin SDK ─────────────────────────
    // Stable release — https://ai.google.dev/edge/litert-lm/overview
    // Provides Engine, EngineConfig, Backend (NPU/GPU/CPU), Conversation APIs.
    // NPU/GPU/CPU acceleration is handled internally; no separate delegate deps.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")

    // ── Persistence: Room for custom dictionary ───────────────────────────────
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // ── Coroutines ─────────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // ── Android core ──────────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // ── JSON (for dictionary noun extraction) ─────────────────────────────────
    implementation("org.json:json:20231013")
}
