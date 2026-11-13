plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// Номер сборки на CI растёт сам, поэтому каждая новая сборка ставится ПОВЕРХ старой (versionCode всегда больше).
val ciRun = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toIntOrNull() ?: 0

android {
    namespace = "ru.gmadmin"
    compileSdk = 34
    defaultConfig {
        applicationId = "ru.gmadmin.helper"
        minSdk = 26
        targetSdk = 34
        versionCode = 400 + ciRun
        versionName = "5.4.4"
    }

    // Постоянный ключ подписи лежит в репозитории (app/gm.jks). Раньше каждая сборка подписывалась новым случайным
    // «debug»-ключом, и Android не давал обновить приложение — приходилось удалять. Теперь подпись всегда одна и та же.
    signingConfigs {
        create("gm") {
            storeFile = file("gm.jks")
            storePassword = "gmadmin-helper"
            keyAlias = "gm"
            keyPassword = "gmadmin-helper"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("gm")
            isMinifyEnabled = false
            isDebuggable = false
        }
        debug { signingConfig = signingConfigs.getByName("gm") }
    }

    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs.useLegacyPackaging = true }
}
dependencies { implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0") }
