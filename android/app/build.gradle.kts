plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.ether404.allknowledge"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ether404.allknowledge"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }
    signingConfigs {
        create("stableDebug") {
            storeFile = rootProject.file("datapipeline-debug.jks")
            storePassword = "datapipeline"
            keyAlias = "datapipeline"
            keyPassword = "datapipeline"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("stableDebug") }
    }
}

kotlin { jvmToolchain(17) }
