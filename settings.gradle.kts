pluginManagement {
<<<<<<< HEAD
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
        }
    }
=======
repositories {
google()
mavenCentral()
gradlePluginPortal()
}
}

dependencyResolutionManagement {
repositories {
google()
mavenCentral()
maven {
url 'https://jitpack.io'
}
}
>>>>>>> 01860cd146f729221cdd69664d5d90c6ef881f81
}

rootProject.name = "GMAdminHelper"

include(":app")
