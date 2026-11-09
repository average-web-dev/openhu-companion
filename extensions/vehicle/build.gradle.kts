plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// The reference extension app (openhu-companion#3): the vehicle plugin's values as an entry in Android Auto
android {
    namespace = "org.openhu.companion.vehicle"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.openhu.companion.vehicle"
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
    lint {
        warningsAsErrors = false
        abortOnError = true
        // nothing plays: no voice search ("play … on …")
        disable += "MissingIntentFilterForMediaSearch"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":extensions:client"))
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
