plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vitallink.helper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vitallink.helper"
        // Health Connect needs Android 8 (API 26) or later; on Android 14+ it's part of the system.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // The production API; the app lets the person change it (e.g. http://10.0.2.2:8000 on the emulator).
        buildConfigField("String", "DEFAULT_SERVER", "\"https://vitallink-9p4d.onrender.com\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    testImplementation("junit:junit:4.13.2")
    // org.json is part of Android; unit tests on the JVM need the real implementation.
    testImplementation("org.json:json:20240303")
}
