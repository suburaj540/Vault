plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shubham.vault"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shubham.vault"
        minSdk = 29                 // Android 10. Keeps file saving permission-free.
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    // Release signing. The key never lives in this repository - it is supplied
    // by GitHub Actions secrets at build time. Without it, only debug builds work.
    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
}
