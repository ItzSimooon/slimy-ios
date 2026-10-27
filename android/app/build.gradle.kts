plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Screenshot tests: recordRoborazziDebug writes PNGs to build/outputs/roborazzi.
    alias(libs.plugins.roborazzi)
}

// Release signing comes from the environment (CI secrets or a local shell),
// never from the repo. Without it, release builds use the debug key.
val releaseKeystore = System.getenv("SLIMY_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "de.hyprnode.slimy"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.hyprnode.slimy"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("SLIMY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SLIMY_KEY_ALIAS")
                keyPassword = System.getenv("SLIMY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Every shipped binary is shrunk and obfuscated with R8.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    testOptions {
        unitTests {
            // Robolectric needs the merged manifest, resources and assets
            // (sprites.json, the Pixelify font) for the screenshot tests.
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "3g"
                // Compose renders for real (Skia), not as empty Robolectric shadows.
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            }
        }
    }

    // The same pixel art and font as the iPhone app, straight from Shared/.
    sourceSets["main"].assets.srcDirs("src/main/assets", "../../Shared/Sprites", "../../Shared/Fonts", "../../Resources/Sounds")
}

dependencies {
    implementation(project(":model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.play.review.ktx)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    // Screenshot tests on the JVM (src/test/.../screenshots): no emulator needed.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
