plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zyzyj.dflow"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zyzyj.dflow"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.3.8-test1"

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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
