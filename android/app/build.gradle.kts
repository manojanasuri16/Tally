import org.jetbrains.kotlin.compose.compiler.gradle.ComposeFeatureFlag

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tally.book"
    compileSdk = 36

    defaultConfig {
        // NEVER change applicationId: it would install as a different app and strand the data.
        applicationId = "com.tally.book"
        minSdk = 29
        targetSdk = 36
        versionCode = 13
        versionName = "0.8.0"
        // The relay (Supabase) project, from ~/.gradle/gradle.properties or -P; empty means sync is off.
        buildConfigField("String", "SUPABASE_URL", "\"${providers.gradleProperty("TALLY_SUPABASE_URL").orElse("").get()}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${providers.gradleProperty("TALLY_SUPABASE_ANON_KEY").orElse("").get()}\"")
    }

    signingConfigs {
        // Release key lives outside the repo (~/.gradle/gradle.properties). Without it a release build fails (below): it never
        // falls back to the debug key, which would make an APK that cannot update the installed app.
        val storeFile = providers.gradleProperty("TALLY_STORE_FILE").orNull
        if (storeFile != null && file(storeFile).exists()) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = providers.gradleProperty("TALLY_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("TALLY_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("TALLY_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug { versionNameSuffix = "-debug" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

// A release APK is only ever signed with the release key: packaging one without it stops the build (debug builds and lint
// are unaffected). scripts/release.sh also checks the certificate after the build.
val releaseKeyMissing = android.signingConfigs.findByName("release") == null
tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        if (releaseKeyMissing) throw GradleException(
            "Release signing key missing: set TALLY_STORE_FILE (an existing file), TALLY_STORE_PASSWORD, TALLY_KEY_ALIAS and " +
                "TALLY_KEY_PASSWORD in ~/.gradle/gradle.properties. A release is never signed with the debug key."
        )
    }
}

composeCompiler {
    // Forms edit a plain Obj draft in place and bump a counter to redraw (FormState.tick, `tick.intValue`). Strong skipping
    // compares that draft by identity, sees "unchanged" and skips every child, so a choice is stored but never shown.
    // With it off, composables taking an Obj always rerun, which is what these screens rely on.
    featureFlags.add(ComposeFeatureFlag.StrongSkipping.disabled())
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.biometric)
    implementation(libs.fragment.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.splashscreen)
    // Scanning a family code: CameraX for the preview and frames, ZXing's pure-Java decoder (no Play Services).
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    // The daily backup into the folder the person picked (data/Backups.kt).
    implementation(libs.androidx.work.runtime)
    // The "Next payment" home-screen widget (widget/).
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    testImplementation(libs.junit)
}
