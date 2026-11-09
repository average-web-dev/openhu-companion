plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// The local player: the music plugin's library and player as a media app in Android Auto, the first extension
// (openhu-companion#3). It reaches the head unit through the companion app.
android {
    namespace = "org.openhu.companion.music"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.openhu.companion.music"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { warningsAsErrors = false; abortOnError = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":extensions:client"))
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json)
}
