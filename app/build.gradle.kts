import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// "Ongea" AI conversation mode (recreates ChatGPT's voice-conversation UX)
// talks to the OpenAI Chat Completions API. The key lives in
// local.properties (gitignored, never committed) and is baked into a
// BuildConfig field at compile time — never hardcoded in source, never
// logged. If the property is missing, fall back to an empty string so a
// clean checkout still compiles; the chat screen handles the empty-key
// case by announcing a clear error instead of crashing.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val openAiApiKey: String = localProperties.getProperty("OPENAI_API_KEY", "")

android {
    namespace = "ai.pivotstudio.via.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.pivotstudio.via.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-scaffold"

        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiApiKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Restrict to arm64-v8a for sideload/debug builds: the sherpa-onnx
        // AAR bundles onnxruntime's native .so per-ABI (~30MB each), so all
        // 4 ABIs balloons the APK well past 100MB with no models bundled.
        // arm64-v8a alone covers virtually every phone sold since ~2017.
        // Drop this filter only for a real multi-ABI release build.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    // HorizontalPager for swipe-between-menus navigation (PLAN.md Phase 4,
    // gestures-only reset) — official, battle-tested swipe mechanism
    // instead of a hand-rolled pointer-input gesture detector, after two
    // rounds of real bugs (state corruption after the first gesture) in
    // that custom code. Matches the thesis's ViewPager-style Simu/Ujumbe
    // swipe navigation.
    implementation("androidx.compose.foundation:foundation")

    // sherpa-onnx Kotlin bindings. Not published to Maven Central/Google's
    // repo — k2-fsa ships it only as a GitHub release .aar asset. Bundles
    // libonnxruntime.so + its own JNI/C++ native libs per-ABI, no separate
    // onnxruntime dependency needed. This version (1.13.8) already exposes
    // OfflineOmnilingualAsrCtcModelConfig (verified via javap, see
    // app/libs/README.md) — the Omnilingual ASR CTC model family used by
    // this app's TranscriptionEngine. See app/libs/README.md to download it.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    // Coroutines — single-consumer Channel for ordered audio buffer draining,
    // and for the voice-confirm-before-action pipeline shared by Simu/Ujumbe.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
