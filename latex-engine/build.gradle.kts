plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.omariskandarani.livelatexapp.latex.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "x86", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
