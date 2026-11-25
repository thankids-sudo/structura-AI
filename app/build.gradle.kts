plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.structura.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.structura.app"
        minSdk = 26
        targetSdk = 34
        // CI run number → every build is a higher version, so it installs as an update over the previous one
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.5." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // One fixed key for every build (debug and release), so new APKs install over old ones.
    // This key is for sideloading only. Use a private key before any Play Store upload.
    signingConfigs {
        create("structura") {
            storeFile = rootProject.file("keystore/structura-debug.jks")
            storePassword = "structura"
            keyAlias = "structura"
            keyPassword = "structura"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("structura")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("structura")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)
}
