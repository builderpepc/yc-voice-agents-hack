import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProps = Properties().also { props ->
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
}

android {
    namespace = "com.example.wearableai"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.example.wearableai"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        val metaAppId = localProps.getProperty("meta_wearables_app_id", "YOUR_APP_ID_HERE")
        val metaClientToken = localProps.getProperty("meta_wearables_client_token", "YOUR_CLIENT_TOKEN_HERE")
        val geminiApiKey = localProps.getProperty("gemini_api_key", "")
        buildConfigField("String", "META_WEARABLES_APP_ID", "\"$metaAppId\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        manifestPlaceholders["META_WEARABLES_APP_ID"] = metaAppId
        manifestPlaceholders["META_WEARABLES_CLIENT_TOKEN"] = metaClientToken
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":shared"))

    // Android UI
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Meta Wearables Device Access Toolkit
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)

    // Google AI (Gemini cloud fallback)
    implementation(libs.google.ai.generativeai)
}
