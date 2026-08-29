plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android { namespace = "com.ether404.allknowledge"; compileSdk = 35
    defaultConfig { applicationId = "com.ether404.allknowledge"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "0.2.0" }
}

kotlin { jvmToolchain(17) }
