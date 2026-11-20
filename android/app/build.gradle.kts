import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Package names of the T3 Code Android app (release first, then preview). Taps open whichever is installed.
val t3AppPackages = listOf("com.t3tools.t3code", "com.t3tools.t3code.preview")

android {
    namespace = "dev.local.t3widget"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.local.t3widget"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.6.0"
        buildConfigField("String", "T3_APP_PACKAGES", "\"${t3AppPackages.joinToString(",")}\"")
    }

    signingConfigs {
        // Stable release key. Every update must be signed with the same key as the installed app, or Android
        // refuses to install it. Settings come from android/keystore.properties (gitignored), then environment
        // variables, then defaults: %USERPROFILE%\.t3widget\signing\t3widget.keystore with the standard Android
        // debug credentials (that file is a copy of the debug key the first builds were signed with).
        create("release") {
            val props = Properties()
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.isFile) propsFile.inputStream().use { props.load(it) }
            fun setting(prop: String, env: String, default: String): String =
                props.getProperty(prop)?.takeIf { it.isNotBlank() }
                    ?: System.getenv(env)?.takeIf { it.isNotBlank() }
                    ?: default
            val defaultStore = File(System.getProperty("user.home"), ".t3widget/signing/t3widget.keystore").path
            storeFile = file(setting("storeFile", "T3WIDGET_KEYSTORE", defaultStore))
            storePassword = setting("storePassword", "T3WIDGET_KEYSTORE_PASSWORD", "android")
            keyAlias = setting("keyAlias", "T3WIDGET_KEY_ALIAS", "androiddebugkey")
            keyPassword = setting("keyPassword", "T3WIDGET_KEY_PASSWORD", "android")
        }
    }

    buildTypes {
        // Small, minified build signed with the stable release key (see signingConfigs above).
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
        resources.excludes += setOf("META-INF/*.version", "META-INF/**/LICENSE*", "kotlin/**", "DebugProbesKt.bin")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}
