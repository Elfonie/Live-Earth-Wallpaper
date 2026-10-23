import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.earthlive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.earthlive"
        minSdk = 26 // java.time, GLES 3.0
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
        (project.findProperty("appIdSuffix") as String?)?.let { applicationIdSuffix = it } // ./gradlew assembleRelease -PappIdSuffix=.test: installs next to the real app
    }

    // Release signing: keystore.properties (storeFile, storePassword, keyAlias, keyPassword) in the project folder, never committed.
    // Without it the release build stays unsigned.
    val signing = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
    signingConfigs {
        if (signing.containsKey("storeFile")) {
            create("release") {
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true // BuildConfig.DEBUG: R8 removes the profiling code from release builds
    }

    androidResources {
        noCompress += listOf("ktx", "bin") // stored uncompressed so that they can be memory-mapped straight from the APK
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
