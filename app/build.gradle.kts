plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.omariskandarani.livelatexapp"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.omariskandarani.livelatexapp"
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

    sourceSets {
        getByName("main") {
            // Tell Android where to find the Rust compiled libraries
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // Ensure 64-bit and 32-bit architectures are split correctly
    packaging {
        jniLibs.keepDebugSymbols.add("**/*.so")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}