plugins {
    id("com.android.application")
}

android {
    namespace = "com.repairip.module"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.repairip.module"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"
    }

    buildFeatures {
        prefab = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // DexKit ships a native .so; ensure it is extracted from the APK
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // LSPosed LibXposed API 102 (compile-only — provided by the framework at runtime)
    compileOnly(libs.libxposed.api)

    // DexKit — runtime DEX class/method finder (replaces static dexlib2 analysis)
    implementation(libs.dexkit)
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}
