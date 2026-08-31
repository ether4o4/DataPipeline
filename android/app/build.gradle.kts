plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.ether404.allknowledge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ether404.allknowledge"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.5.2"
    }

    signingConfigs {
        create("stable") {
            val keystoreFile = rootProject.file("signing/datapipeline-release.keystore")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "datapipeline"
                keyAlias = "datapipeline"
                keyPassword = "datapipeline"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("stable")
            isMinifyEnabled = false
        }
    }
}

kotlin { jvmToolchain(17) }
