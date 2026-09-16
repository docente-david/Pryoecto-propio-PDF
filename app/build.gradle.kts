plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.imagenesapdf.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.imagenesapdf.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
        resourceConfigurations += listOf("es")
    }

    // Llave propia para firmar el APK de distribución. La carpeta keystore/ NO se sube al
    // repositorio (ver .gitignore); si no existe, el release se firma con la llave de depuración.
    // Conserva la llave original: las actualizaciones deben firmarse con la misma.
    val keystoreFile = rootProject.file("keystore/imagenes-a-pdf.jks")
    signingConfigs {
        create("release") {
            storeFile = keystoreFile
            storePassword = "imagenesapdf2026"
            keyAlias = "imagenesapdf"
            keyPassword = "imagenesapdf2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (keystoreFile.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
