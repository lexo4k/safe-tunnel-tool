plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val livreNetGitSha = System.getenv("GITHUB_SHA")?.take(7) ?: "local"

android {
    namespace = "com.livrenet.android"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.livrenet.android"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.0"
        buildConfigField("String", "GIT_SHA", "\"$livreNetGitSha\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets.getByName("main").jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
