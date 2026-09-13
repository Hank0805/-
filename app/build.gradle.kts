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
        versionCode = 6
        versionName = "0.5.0"
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
}
