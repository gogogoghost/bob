plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "cc.jaxy.bob.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "cc.jaxy.bob.app"
        minSdk = 26
        targetSdk = 37
        // CI overrides via -PversionCode/-PversionName from the git tag.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Release key from env (CI secrets, still ANLOBEHUB_* names) or ~/.gradle/gradle.properties.
        // Absent -> unsigned release build (still assembles for verification).
        create("release") {
            val ksPath = System.getenv("ANLOBEHUB_KEYSTORE_PATH")
                ?: project.findProperty("anlobehub.keystore.path") as String?
            val ksPass = System.getenv("ANLOBEHUB_KEYSTORE_PASSWORD")
                ?: project.findProperty("anlobehub.keystore.password") as String?
            val alias = System.getenv("ANLOBEHUB_KEY_ALIAS")
                ?: project.findProperty("anlobehub.key.alias") as String?
            val keyPass = System.getenv("ANLOBEHUB_KEY_PASSWORD")
                ?: project.findProperty("anlobehub.key.password") as String?
            if (!ksPath.isNullOrBlank() && !ksPass.isNullOrBlank() && !alias.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = alias
                keyPassword = keyPass?.takeIf { it.isNotBlank() } ?: ksPass
            }
        }
    }

    // True only when signingConfigs.release got a store file (all three set).
    val hasReleaseKeystore = !((System.getenv("ANLOBEHUB_KEYSTORE_PATH")
        ?: project.findProperty("anlobehub.keystore.path") as String?).isNullOrBlank() ||
        (System.getenv("ANLOBEHUB_KEYSTORE_PASSWORD")
        ?: project.findProperty("anlobehub.keystore.password") as String?).isNullOrBlank() ||
        (System.getenv("ANLOBEHUB_KEY_ALIAS")
        ?: project.findProperty("anlobehub.key.alias") as String?).isNullOrBlank())
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:agents"))
    implementation(project(":feature:models"))
    implementation(project(":feature:settings"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.core.splashscreen)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.okhttp)
    implementation(libs.markdown.m3)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
