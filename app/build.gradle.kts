plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.androidxBaselineProfile)
}

// ObjectBox plugin applied imperatively because it ships without a plugin
// marker (classpath wired in the root build.gradle.kts). Apply must come
// after the `android` plugin so the AGP variants are available.
apply(plugin = "io.objectbox")

android {
    namespace = "com.localllm.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.localllm.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
    }

    signingConfigs {
        create("release") {
            // Read from ~/.gradle/gradle.properties or env. NEVER hardcode.
            val keystorePath = (findProperty("LOCALLLM_KEYSTORE_PATH") as String?)
                ?: System.getenv("LOCALLLM_KEYSTORE_PATH")
            val keystorePassword = (findProperty("LOCALLLM_KEYSTORE_PASSWORD") as String?)
                ?: System.getenv("LOCALLLM_KEYSTORE_PASSWORD")
            val keyAlias = (findProperty("LOCALLLM_KEY_ALIAS") as String?)
                ?: System.getenv("LOCALLLM_KEY_ALIAS")
            val keyPassword = (findProperty("LOCALLLM_KEY_PASSWORD") as String?)
                ?: System.getenv("LOCALLLM_KEY_PASSWORD")

            if (keystorePath != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
                storeFile = file(keystorePath)
                this.storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseCfg = signingConfigs.getByName("release")
            signingConfig = if (releaseCfg.storeFile != null) releaseCfg else signingConfigs.getByName("debug")
        }
        debug {
            // unchanged
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            // LiteRT-LM 0.11.0 AAR ships JNI .so files for arm64-v8a only;
            // armeabi-v7a is intentionally excluded. x86 / x86_64 are dropped —
            // emulator inference on x86 is unusably slow anyway (see docs/development.md).
            include("arm64-v8a")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.0}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Markdown parsing (Java, no Android dependency) for assistant message rendering
    implementation(libs.commonmark)

    // ONNX Runtime for the /v1/embeddings endpoint (BGE-class sentence
    // embeddings). Adds ~30 MB across ABIs but only ships JNI .so files for
    // arm64-v8a (see splits block). Engines for the LM remain on LiteRT-LM —
    // this is the embedding side only.
    implementation(libs.onnxruntime.android)

    // Async, Flow-native settings persistence (replaces SharedPreferences)
    implementation(libs.androidx.datastore.preferences)

    // WorkManager — drives the periodic background warm-up job that
    // pre-loads the selected engine while the device is idle so the first
    // user request after a long pause doesn't pay the full engine-init cost.
    implementation(libs.androidx.work.runtime.ktx)

    // ObjectBox on-device vector store. Backs the /v1/documents + /v1/search
    // endpoints with an HNSW-indexed embedding column for sub-millisecond kNN.
    implementation(libs.objectbox.android)
    implementation(libs.objectbox.kotlin)

    // Ktor Server (OpenAI-compatible HTTP API)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)

    // LiteRT-LM (on-device Gemma 4 / 3n via Google's LLM runtime; replaces MediaPipe tasks-genai)
    implementation(libs.litertlm.android)

    // ML Kit GenAI Prompt API — Gemini Nano via AICore. Optional second
    // backend exposed as the magic model id `gemini-nano-aicore`, lets us
    // bypass the LiteRT-LM path on devices where AICore is available
    // (Pixel 8+). Beta — see
    // developers.google.com/ml-kit/genai/aicore-dev-preview.
    implementation(libs.mlkit.genai.prompt)

    // OkHttp for the in-app Chat tab that hits the local server
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockito.core)
    testImplementation(libs.turbine)

    // R8 consumes the profile produced by the :macrobenchmark module to
    // AOT-compile the hot startup paths in release builds.
    baselineProfile(project(":macrobenchmark"))
}
