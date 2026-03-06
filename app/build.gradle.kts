plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.omariskandarani.livelatexapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omariskandarani.livelatexapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Cloud: add GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET, GOOGLE_WEB_CLIENT_ID in gradle.properties or local.properties
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"${project.findProperty("GITHUB_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "GITHUB_CLIENT_SECRET", "\"${project.findProperty("GITHUB_CLIENT_SECRET") ?: ""}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${project.findProperty("GOOGLE_WEB_CLIENT_ID") ?: ""}\"")
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
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation(libs.play.services.auth)
    implementation(libs.okhttp)
    implementation(libs.security.crypto)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

