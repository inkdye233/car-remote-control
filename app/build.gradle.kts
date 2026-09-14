plugins {
    id("com.android.application")
}

android {
    namespace = "com.embedded.carremotecontrol"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.embedded.carremotecontrol"
        minSdk = 23
        targetSdk = 34
        versionCode = 102
        versionName = "1.0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal-use release: installable without maintaining a production keystore.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}
