plugins {
    id("com.android.application")
}

android {
    namespace = "com.rebron1900.fcitx5enhanced"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rebron1900.fcitx5enhanced"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "1.7.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(files("libs/xposed-api-101.0.1.jar"))
    implementation("com.caverock:androidsvg:1.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime:2.10.1")
}
