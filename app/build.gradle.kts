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

        // CMake configuration
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++14", "-frtti", "-fexceptions")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }

        // Specify ABIs to build
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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

    // CMake configuration
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.18.1"
        }
    }

    // Ensure jniLibs are packaged
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

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