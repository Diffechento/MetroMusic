import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing, read from an untracked `keystore.properties` in the project root:
//
//   storeFile=metromusic.jks
//   storePassword=...
//   keyAlias=metromusic
//   keyPassword=...
//
// Without that file the release variant is signed with the debug key instead. That is not good
// enough to publish, but it *is* installable, which an unsigned APK is not — and unsigned is what
// this produced before, which is why release builds could not be put on a phone at all.
val releaseKeystore = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

// Which signing config the release variant uses, resolved *here* so that the line that reads it is
// one word after the `=`. F-Droid's builder edits this file before building — it deletes the whole
// `signingConfigs { … }` block and every line matching `signingConfig = <no spaces>`, because it
// signs with its own key and wants an unsigned APK. Written the obvious way, as
// `findByName("release") ?: getByName("debug")` over two lines, only the first line matched: what was
// left was a dangling `?: signingConfigs.getByName("debug")` inside `release { }`, and their build
// died in the Kotlin compiler. Keep the assignment below a single token.
val releaseSigningConfigName = if (releaseKeystore.isNotEmpty()) "release" else "debug"

// Last.fm credentials, read from the untracked `local.properties`:
//
//   LASTFM_API_KEY=...
//   LASTFM_API_SECRET=...
//
// These identify *the application*, not the user, so they cannot be committed — and a build without
// them is not broken: the Last.fm page then asks for a key, and everything else in the app carries on
// as if the feature were switched off.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun localProperty(name: String): String = localProperties.getProperty(name).orEmpty()

android {
    namespace = "com.metromusic"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.metromusic"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.3"

        // The date this version was released, shown on the about page. A literal beside the version
        // rather than the moment of the build: `Date()` here would change on every configure, so no
        // two builds of the same source would agree and Gradle could never call the task up to date.
        buildConfigField("String", "RELEASE_DATE", "\"31 august 2026\"")

        buildConfigField(
            "String",
            "LASTFM_API_KEY",
            "\"${localProperty("LASTFM_API_KEY")}\""
        )
        buildConfigField(
            "String",
            "LASTFM_API_SECRET",
            "\"${localProperty("LASTFM_API_SECRET")}\""
        )
    }

    signingConfigs {
        if (releaseKeystore.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore.getProperty("storeFile"))
                storePassword = releaseKeystore.getProperty("storePassword")
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(releaseSigningConfigName)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // The framework. Compose comes through transitively (it's `api` over there).
    implementation(libs.metrocompose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    // Playback: ExoPlayer plus the session that gives us the notification, lock screen,
    // headset buttons and Bluetooth controls without writing any of them.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.concurrent.futures.ktx)

    // Writing the tags inside the files, which is the only kind of metadata edit that sticks:
    // MediaStore's own metadata columns are read-only from Android 10 on and updates to them are
    // dropped without an error.
    implementation(libs.jaudiotagger)

    // Playlists, favorites and settings are small JSON files in filesDir.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
