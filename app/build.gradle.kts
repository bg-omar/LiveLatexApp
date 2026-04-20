plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.omariskandarani.livelatexapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.omariskandarani.livelatexapp"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Cloud: add GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET, GOOGLE_WEB_CLIENT_ID in gradle.properties or local.properties
        val ghClientId = (project.findProperty("GITHUB_CLIENT_ID") as String?) ?: ""
        val ghSecret = (project.findProperty("GITHUB_CLIENT_SECRET") as String?) ?: ""
        val googleWebClientId = (project.findProperty("GOOGLE_WEB_CLIENT_ID") as String?) ?: ""
        resValue("string", "cloud_github_client_id", ghClientId)
        resValue("string", "cloud_github_client_secret", ghSecret)
        resValue("string", "cloud_google_web_client_id", googleWebClientId)

        val admobAppId = (project.findProperty("ADMOB_APP_ID") as String?)
            ?: "ca-app-pub-3940256099942544~3347511713"
        resValue("string", "admob_app_id", admobAppId)
        val rewardedUnit = (project.findProperty("ADMOB_REWARDED_UNIT_ID") as String?)
            ?: "ca-app-pub-3940256099942544/5224354917"
        resValue("string", "admob_rewarded_unit_id", rewardedUnit)

        val iapProductId = (project.findProperty("IAP_PRO_PRODUCT_ID") as String?) ?: "pro_upgrade"
        buildConfigField("String", "IAP_PRO_PRODUCT_ID", "\"$iapProductId\"")
        val promoSecret = (project.findProperty("PROMO_HMAC_SECRET") as String?) ?: "dev-insecure-change-in-release"
        val promoEscaped = promoSecret.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "PROMO_HMAC_SECRET", "\"$promoEscaped\"")
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
        resValues = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(libs.play.services.auth)
    implementation(libs.okhttp)
    implementation(libs.security.crypto)
    implementation(libs.billing.ktx)
    implementation(libs.play.services.ads)
    implementation(libs.lifecycle.runtime.ktx)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

