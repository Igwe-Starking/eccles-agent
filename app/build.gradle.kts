plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "starking.eccles.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "starking.eccles.agent"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.core)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.preference)
    implementation(libs.cardview)
    implementation(libs.recyclerview)
    implementation(libs.fragment)

    // --- Unit tests (src/test): pure-JVM tests for the command parser, safety guard, matching.
    // The real org.json is needed here because android.jar's org.json is a stub on the local JVM
    // (isReturnDefaultValues would silently return empty values instead of failing). ---
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
