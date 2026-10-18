pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AssinaPDF"

include(":core:stamps")
include(":core:signing")
include(":verify:pdf-jvm")
include(":server:token-broker")

// O módulo Android só entra no build quando há um Android SDK disponível (Android Studio, CI ou
// local.properties com sdk.dir). Sem SDK, os módulos JVM continuam compiláveis e testáveis.
// Para forçar: -Passinapdf.android=true ou false.
val localProps = java.util.Properties().apply {
    val f = file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val forced = providers.gradleProperty("assinapdf.android").orNull?.toBoolean()
val hasSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    localProps.getProperty("sdk.dir") != null
if (forced ?: hasSdk) {
    include(":app")
} else {
    println("AssinaPDF: Android SDK não encontrado; módulo :app ignorado neste build.")
}
