import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.laurio.rangelog"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.laurio.rangelog"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Release key for the share build. keystore.properties (git-ignored) lists storeFile, storePassword,
    // keyAlias and keyPassword; GitHub Actions writes it from repo secrets. Without it, release builds
    // fall back to the debug key, which is fine for testing but can't update a properly signed install.
    val shareKeyProps = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (shareKeyProps != null) create("share") {
            storeFile = rootProject.file(shareKeyProps.getProperty("storeFile"))
            storePassword = shareKeyProps.getProperty("storePassword")
            keyAlias = shareKeyProps.getProperty("keyAlias")
            keyPassword = shareKeyProps.getProperty("keyPassword")
        }
    }

    // One code base, two apps:
    //  personal = Michael's own app: preloaded history, Google sign-in, cloud sync and undo.
    //  share    = for friends: starts empty, everything stays on the phone, no Firebase.
    // Shared code is in src/main; each flavor's extras are in src/personal and src/share.
    flavorDimensions += "edition"
    productFlavors {
        create("personal") {
            dimension = "edition"
            buildConfigField("boolean", "CLOUD_SYNC", "true")
            signingConfig = signingConfigs.getByName("debug")
        }
        create("share") {
            dimension = "edition"
            applicationIdSuffix = ".share" // com.laurio.rangelog.share, installs next to the personal app
            versionNameSuffix = "-share"
            buildConfigField("boolean", "CLOUD_SYNC", "false")
            signingConfig = signingConfigs.findByName("share") ?: signingConfigs.getByName("debug")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signing comes from the flavor above (personal: debug key; share: release key when present).
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// google-services.json only knows the personal app, and the share app doesn't use Firebase,
// so skip the step that reads it for share builds (they also build without the file, e.g. on GitHub).
tasks.configureEach {
    if (name.startsWith("processShare") && name.endsWith("GoogleServices")) enabled = false
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Sync: Firebase (Firestore + Google sign-in)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
