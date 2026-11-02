import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is driven by keystore.properties (not in git). Without it a plain
// assembleRelease stays unsigned instead of failing the build.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use { load(it) }
}
val hasKeystore = keystorePropsFile.isFile

android {
    namespace = "io.github.sd1471123.sdr"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.sd1471123.sdr"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.1.1"
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
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

// The MVP deliberately has no third-party dependencies in the APK: raw USB CDC + a hand written
// FFT. junit is test-only and never ships inside the binary.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
