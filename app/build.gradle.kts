import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Réglages propres à ce PC (jamais publiés) : SDK, identifiant Google Drive, clé de signature.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.alphateam.carnetencre"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alphateam.carnetencre"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Identifiant OAuth Google Drive (« Application de bureau »), lu dans local.properties :
        //   drive.clientId=…apps.googleusercontent.com
        //   drive.clientSecret=…
        // Sans lui, l'envoi sur Drive est simplement indisponible dans les Réglages.
        buildConfigField("String", "DRIVE_CLIENT_ID", "\"${local.getProperty("drive.clientId", "")}\"")
        buildConfigField("String", "DRIVE_CLIENT_SECRET", "\"${local.getProperty("drive.clientSecret", "")}\"")
    }

    // Clé des versions publiques, hors du dépôt (local.properties) :
    //   release.storeFile=keystore/plume-release.jks, release.storePassword, release.keyAlias, release.keyPassword
    // Sans elle, assembleRelease produit un APK non signé.
    val releaseStore = local.getProperty("release.storeFile")?.let { rootProject.file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = local.getProperty("release.storePassword")
                keyAlias = local.getProperty("release.keyAlias")
                keyPassword = local.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    packaging {
        jniLibs {
            pickFirsts += "**/*.so"
        }
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
            )
        }
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Rendu des schémas, tableaux et calendriers que Claude dessine en SVG
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // SDK Onyx : stylet basse latence (TouchHelper) et contrôle e-ink (EpdController)
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.8") {
        exclude(group = "com.android.support")
    }
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.6") {
        exclude(group = "com.android.support")
    }
    // Requis par le SDK Onyx sur Android 11+ (accès aux API système cachées)
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    testImplementation("junit:junit:4.13.2")
}
