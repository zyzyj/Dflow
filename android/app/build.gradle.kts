plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.zyzyj.dflow"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zyzyj.dflow"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.4.1-test4"

        // The APK ships a self-contained Linux userland (proot + Alpine rootfs +
        // node_modules), so it only makes sense on 64-bit ARM. Shipping other
        // ABIs would multiply the download size for no benefit.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            // Test builds install alongside a real one and never touch its data.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BuildConfig carries the versionCode used to decide whether the
        // bundled rootfs needs reinstalling after an upgrade.
        buildConfig = true
    }

    kotlin {
        jvmToolchain(17)
    }

    packaging {
        jniLibs {
            // MUST stay true. With the modern default (extractNativeLibs=false)
            // native libraries are mapped straight from the APK and never
            // appear as files on disk — but we have to exec() libproot.so, and
            // Android only allows execution from the app's nativeLibraryDir,
            // which requires the libraries to be extracted first.
            useLegacyPackaging = true
        }
        resources {
            // The Alpine rootfs and node_modules travel as .tar.gz inside assets;
            // compressing them again would waste build time for no size gain.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        // Assets must survive packaging byte-for-byte: aapt strips the .gz
        // extension and decompresses archives it recognises, which would leave
        // a bare .tar whose name the Kotlin extractor does not expect. Listing
        // the extensions keeps the archives intact.
        noCompress += listOf("gz", "xz", "tar", "tgz")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Networking and images. Coil fetches through DFlow's own image proxy,
    // because the CDN requires headers a plain client does not send.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
