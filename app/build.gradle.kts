plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.n7folder.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.n7folder.player"
        minSdk = 26 // Android 8.0 : icônes adaptatives, DynamicsProcessing gardé par un test d'API (28+)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // APK "release" installable directement : signé avec la clé debug (usage personnel).
            // Pour une diffusion publique, remplacer par une vraie signingConfig (secrets GitHub).
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
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
    }

    lint {
        // Le build "release" ne doit jamais échouer à cause d'un avertissement lint.
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // --- Jetpack Compose (versions pilotées par le BOM) ---
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // --- AndroidX ---
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // --- Media3 : ExoPlayer + MediaLibraryService/MediaSession (utilisés dès l'itération 2) ---
    val media3Version = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    // ListenableFuture (MediaController.buildAsync) : Guava, déjà tiré par Media3, déclaré explicitement
    // pour que Kotlin voie ses types à la compilation.
    implementation("com.google.guava:guava:31.1-android")

    // --- Coil : chargement + cache mémoire/disque des pochettes ---
    implementation("io.coil-kt:coil-compose:2.7.0")

    // --- Tests JVM purs (PathNormalizer) ---
    testImplementation("junit:junit:4.13.2")
}
