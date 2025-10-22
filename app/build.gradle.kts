plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.demoplayvideo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.demoplayvideo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.okhttp)
    // ExoPlayer core
    implementation(libs.androidx.media3.exoplayer)
    // Giao diện UI player (nếu bạn dùng PlayerView)
    implementation(libs.androidx.media3.ui)
    // Nếu bạn phát HLS (m3u8)
    implementation(libs.androidx.media3.exoplayer.hls)
    // (Tuỳ chọn) Adaptive streaming DASH, SmoothStreaming...
    implementation(libs.androidx.media3.exoplayer.dash)
}