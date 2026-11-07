plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace  = "com.anivault"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anivault"
        minSdk        = 26
        targetSdk     = 34
        versionCode   = 2
        versionName   = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // The UI is English-only; dropping other library translations trims the APK.
        resourceConfigurations += listOf("en")

        // Room schema export directory
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental",    "true")
        }
    }

    // One APK per CPU architecture (smaller downloads), plus a universal APK that runs anywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            // FOSS/direct-distribution builds are signed with Android's generated debug key.
            // This is not suitable for Play Store publication or secure long-term updates.
            signingConfig      = signingConfigs.getByName("debug")
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable     = true
            isMinifyEnabled  = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ── AndroidX Core ──────────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.fragment.ktx)

    // ── Compose ────────────────────────────────────────────────────────────
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.ext)
    debugImplementation(libs.compose.ui.tooling)

    // ── Lifecycle ──────────────────────────────────────────────────────────
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // ── Room ───────────────────────────────────────────────────────────────
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.sqlcipher)
    implementation(libs.sqlite.ktx)
    ksp(libs.room.compiler)

    // ── Hilt ───────────────────────────────────────────────────────────────
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // ── Network & Scraping ─────────────────────────────────────────────────
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.jsoup)
    // Jsoup references JSpecify nullness annotations; include them for R8 release shrinking.
    implementation(libs.jspecify)

    // ── Coroutines ─────────────────────────────────────────────────────────
    implementation(libs.coroutines.android)

    // ── Image loading ──────────────────────────────────────────────────────
    implementation(libs.coil.compose)
    // Installs the bundled baseline profile (src/main/baseline-prof.txt) at first launch for faster startup.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    // ── Biometrics ─────────────────────────────────────────────────────────
    implementation(libs.biometric)

    // ── DataStore ─────────────────────────────────────────────────────────
    implementation(libs.datastore.preferences)

    // ── Background work (reminders) ────────────────────────────────────────
    implementation(libs.work.runtime.ktx)

    // ── Unit tests (pure JVM) ──────────────────────────────────────────────
    testImplementation(libs.junit)
    // Android's org.json is a stub in JVM unit tests; use the real implementation there.
    testImplementation("org.json:json:20231013")
    testImplementation(libs.coroutines.test)
}
