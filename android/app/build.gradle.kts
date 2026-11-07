import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing details live in android/keystore.properties, which is git-ignored.
// Without that file, release builds stay unsigned (and dev builds never need it).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.q43b.tgflip"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.q43b.tgflip"
        minSdk = 24
        targetSdk = 37
        // versionCode must go up with every release, or Android won't install it as an update.
        // versionName matches the git tag, without the "v" (tag v0.1.0-alpha).
        versionCode = 50
        versionName = "0.17.0-beta"
        // The era's name, shown after the version (from 0.13.0-beta): Almost Liquid for
        // 0.13.0-beta on, Melted for 1.0.0; release titles use it too
        buildConfigField("String", "CODENAME", "\"Almost Liquid\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            signingConfig = signingConfigs.findByName("release")
        }
        // Dev builds are a separate app ("TG Flip (dev)", its own name in src/debug),
        // so they install next to the release instead of clashing with its signing key
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
        }
    }
    buildFeatures {
        buildConfig = true  // BuildConfig.VERSION_NAME, sent to the server
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.okhttp)
    // Shrinking videos on the phone before sending (VideoOptimizer), with its video chip
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}