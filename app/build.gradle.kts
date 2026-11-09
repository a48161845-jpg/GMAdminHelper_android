plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "ru.gmadmin"
    compileSdk = 34
    defaultConfig { applicationId = "ru.gmadmin.helper"; minSdk = 26; targetSdk = 34; versionCode = 4; versionName = "4.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs.useLegacyPackaging = true }
}
dependencies { implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0") }
