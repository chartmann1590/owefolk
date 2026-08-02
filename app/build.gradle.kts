import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

apply(plugin = "com.google.gms.google-services")
apply(plugin = "com.google.firebase.crashlytics")
apply(plugin = "com.google.firebase.firebase-perf")

val releaseKeystorePath = providers.environmentVariable("OWEFOLK_KEYSTORE_PATH")
val releaseKeystorePassword = providers.environmentVariable("OWEFOLK_KEYSTORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("OWEFOLK_KEY_ALIAS").orElse("key0")
val releaseKeyPassword = providers.environmentVariable("OWEFOLK_KEY_PASSWORD")
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
fun protectedValue(name: String): String? = providers.environmentVariable(name).orNull ?: localProperties.getProperty(name)
val admobAppId = protectedValue("ADMOB_APP_ID")
val admobBannerId = protectedValue("ADMOB_BANNER_ID")
val admobInterstitialId = protectedValue("ADMOB_INTERSTITIAL_ID")
val admobTestDeviceId = protectedValue("ADMOB_TEST_DEVICE_ID").orEmpty()
val buildingRelease = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
if (buildingRelease) {
    require(!admobAppId.isNullOrBlank() && !admobBannerId.isNullOrBlank() && !admobInterstitialId.isNullOrBlank()) {
        "Release builds require ADMOB_APP_ID, ADMOB_BANNER_ID, and ADMOB_INTERSTITIAL_ID"
    }
}

android {
    namespace = "com.charles.owefolk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.charles.owefolk"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            if (releaseKeystorePath.isPresent) {
                storeFile = file(releaseKeystorePath.get())
                storePassword = releaseKeystorePassword.orNull
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.orNull ?: releaseKeystorePassword.orNull
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
            if (releaseKeystorePath.isPresent) signingConfig = signingConfigs.getByName("release")
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "ADMOB_BANNER_ID", "\"ca-app-pub-3940256099942544/9214589741\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"ca-app-pub-3940256099942544/1033173712\"")
            buildConfigField("String", "ADMOB_TEST_DEVICE_ID", "\"$admobTestDeviceId\"")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["admobAppId"] = admobAppId.orEmpty()
            buildConfigField("String", "ADMOB_BANNER_ID", "\"${admobBannerId.orEmpty()}\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"${admobInterstitialId.orEmpty()}\"")
            buildConfigField("String", "ADMOB_TEST_DEVICE_ID", "\"$admobTestDeviceId\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.credential)
    implementation(libs.androidx.credential.play)
    implementation(libs.googleid)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.google.mobile.ads)
    implementation(libs.google.ump)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.perf)
    implementation(libs.firebase.config)
    implementation(libs.firebase.appcheck)
    debugImplementation(libs.firebase.appcheck.debug)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
