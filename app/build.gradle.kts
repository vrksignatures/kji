plugins {
    id("com.android.application")
}

android {
    namespace = "com.vrksignatures.sudhaautobot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vrksignatures.sudhaautobot"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            // Debug signing keeps `assembleRelease` usable without publishing a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        // 17: NewPipe Extractor (the YouTube look-up, see below) is built for Java 17.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // The same library uses newer java.* classes than Android 7 - 12 have; this adds them.
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            // Licence texts, notices and build leftovers that several libraries ship under
            // the same name; two files with one path stop the APK from being packed.
            // What each library is and its licence: docs/THIRD-PARTY.md.
            excludes += setOf(
                "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES",
                "META-INF/*.md", "META-INF/AL2.0", "META-INF/LGPL2.1",
                "META-INF/INDEX.LIST", "META-INF/versions/**"
            )
        }
    }

    lint {
        // Lint is not a build gate for a single-module utility app.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Everything the bot itself does - reading the screen, pressing, the ledger - uses the
    // phone's own (framework) APIs and nothing else. The libraries below are for the music
    // only (MusicEngine, YouTubeAudio); no class of the bot refers to them.

    // ExoPlayer: plays the songs from the internet, two at a time for the cross-fade.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    // Only for a song YouTube offers as an HLS list instead of a separate sound stream.
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")

    // NewPipe Extractor: turns a YouTube search or page into the address of a sound-only
    // stream. GPL-3.0-or-later (see docs/THIRD-PARTY.md). Not an official YouTube interface:
    // when YouTube changes, raise this version (https://github.com/TeamNewPipe/NewPipeExtractor/releases).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // Keeps the Kotlin runtime pieces that AndroidX pulls in on one version (otherwise
    // "Duplicate class kotlin.collections.jdk8..." can stop the build).
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.22"))
}
