plugins {
    id("com.android.application")
}

android {
    namespace = "jp.minobs.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.minobs.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 10
        versionName = "0.6.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation("com.herohan:UVCAndroid:1.0.13")
}
