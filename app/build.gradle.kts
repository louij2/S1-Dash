plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.louij2.s1dash"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.louij2.s1dash"
        minSdk = 28 // Android 9: the MITM box. The Carlinkit T-Box is Android 13.
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
    }

    // Release signing key is kept OUT of the repo. Point S1DASH_KEYSTORE at the
    // keystore and S1DASH_KEYSTORE_PASSWORD at its password (see scripts/release.sh).
    // Without them a release build is left unsigned; debug builds use the normal
    // Android debug key.
    val keystore = System.getenv("S1DASH_KEYSTORE")
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("S1DASH_KEYSTORE_PASSWORD")
            keyAlias = "s1dash"
            keyPassword = System.getenv("S1DASH_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
