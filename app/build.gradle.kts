plugins {
    alias(libs.plugins.android.app)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.obscura.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.obscura.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    // ObscuraKit library
    implementation(project(":lib"))

    // Android SQLDelight driver
    implementation(libs.sqldelight.android)
    implementation(libs.sqldelight.coroutines)

    // libsignal Android native (replaces JVM variant from lib module)
    implementation(libs.libsignal.android)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)

    // Coroutines
    implementation(libs.coroutines.core)

    // Secure storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // SQLCipher — encrypted SQLite (same as Signal Android)
    implementation(libs.sqlcipher)
    implementation(libs.sqlite)

    // Desugaring (required by libsignal-android)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
