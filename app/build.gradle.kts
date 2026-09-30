plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Keep generated files outside the OneDrive checkout to avoid sync-related locks.
layout.buildDirectory.set(File(gradle.gradleUserHomeDir, "voice-macro-studio/build/app"))

android {
    namespace = "dev.voicemacro.studio"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.voicemacro.studio"
        minSdk = 26
        targetSdk = 36
        versionCode = 26
        versionName = "0.4.0-quantity"
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
    val room_version = "2.7.2"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    ksp("androidx.room:room-compiler:$room_version")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.mockk:mockk:1.13.13")
}
