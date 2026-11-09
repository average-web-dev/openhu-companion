import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing with the upload key (Play App Signing): keystore.properties next to this file or the
// environment (CI secrets). Without it the release bundle is unsigned and only the debug build can be installed.
val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
    System.getenv("OPENHU_UPLOAD_KEYSTORE")?.let { put("storeFile", it) }
    System.getenv("OPENHU_UPLOAD_KEYSTORE_PASSWORD")?.let { put("storePassword", it) }
    System.getenv("OPENHU_UPLOAD_KEY_ALIAS")?.let { put("keyAlias", it) }
    System.getenv("OPENHU_UPLOAD_KEY_PASSWORD")?.let { put("keyPassword", it) }
}

android {
    namespace = "org.openhu.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.openhu.companion"
        minSdk = 26
        targetSdk = 36
        // versionCode: raise with every Play upload
        versionCode = 3
        versionName = "1.0.0"
    }

    signingConfigs {
        if (signing.getProperty("storeFile") != null) {
            create("upload") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    lint { warningsAsErrors = false; abortOnError = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.car.app)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // org.json in unit tests (Android's own is a stub there)
    testImplementation(libs.json)
}
