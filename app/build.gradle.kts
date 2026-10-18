import java.util.Properties

plugins {
    id("com.android.application") version libs.versions.agp.get()
    id("org.jetbrains.kotlin.android") version libs.versions.kotlin.get()
    id("org.jetbrains.kotlin.plugin.compose") version libs.versions.kotlin.get()
    id("org.jetbrains.kotlin.plugin.serialization") version libs.versions.kotlin.get()
}

// Configuração local NÃO versionada (veja signing-config.example.properties).
val localConfig = Properties().apply {
    val f = rootProject.file("signing-config.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Variável vazia (segredo do GitHub não cadastrado) conta como ausente, para não anular o padrão.
fun cfg(key: String, default: String = ""): String =
    (System.getenv(key.uppercase().replace('.', '_'))?.takeIf { it.isNotBlank() }
        ?: localConfig.getProperty(key)?.takeIf { it.isNotBlank() } ?: default).trim()
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Retorno HTTPS publicado na Railway (projeto "AssinaPDF retorno"); o painel do SafeID não aceita
// esquemas próprios como assinapdf://. O Android abre o app direto por App Link verificado.
val redirectUri = cfg("safeid.redirectUri", "https://retorno-production.up.railway.app/oauth/callback")
val redirectScheme = redirectUri.substringBefore("://")
val redirectHost = redirectUri.substringAfter("://").substringBefore("/")
val redirectPath = "/" + redirectUri.substringAfter("://").substringAfter("/", "")

android {
    namespace = "br.com.bkm.assinapdf"
    compileSdk = 36

    defaultConfig {
        applicationId = "br.com.bkm.assinapdf"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SAFEID_BASE_URL", quoted(cfg("safeid.baseUrl", "https://pscsafeweb.safewebpss.com.br/Service/Microservice/OAuth/api/v0/oauth")))
        buildConfigField("String", "SAFEID_CLIENT_ID", quoted(cfg("safeid.clientId", "assinapdf-rixiycui")))
        buildConfigField("String", "SAFEID_REDIRECT_URI", quoted(redirectUri))
        buildConfigField("String", "SAFEID_SCOPE", quoted(cfg("safeid.scope", "signature_session")))
        buildConfigField("int", "SAFEID_LIFETIME", cfg("safeid.lifetime", "604800").toIntOrNull()?.toString() ?: "604800")
        buildConfigField("String", "SAFEID_TOKEN_MODE", quoted(cfg("safeid.tokenMode", "BROKER")))
        buildConfigField("String", "SAFEID_BROKER_URL", quoted(cfg("safeid.brokerUrl", "https://retorno-production.up.railway.app")))
        buildConfigField("String", "SAFEID_BROKER_KEY", quoted(cfg("safeid.brokerKey")))
        buildConfigField("String", "SAFEID_PATH_AUTHORIZE", quoted(cfg("safeid.path.authorize", "authorize")))
        buildConfigField("String", "SAFEID_PATH_TOKEN", quoted(cfg("safeid.path.token", "token")))
        buildConfigField("String", "SAFEID_PATH_DISCOVERY", quoted(cfg("safeid.path.certificateDiscovery", "certificate-discovery")))
        buildConfigField("String", "SAFEID_PATH_SIGNATURE", quoted(cfg("safeid.path.signature", "signature")))
        buildConfigField("String", "SAFEID_HASH_ALG_FIELD", quoted(cfg("safeid.hashAlgorithmField", "hash_algorithm")))
        buildConfigField("String", "SAFEID_POLICY_OID", quoted(cfg("safeid.policy.oid")))
        buildConfigField("String", "SAFEID_POLICY_HASH_ALG", quoted(cfg("safeid.policy.hashAlgorithmOid")))
        buildConfigField("String", "SAFEID_POLICY_HASH", quoted(cfg("safeid.policy.hashBase64")))
        buildConfigField("String", "SAFEID_POLICY_URI", quoted(cfg("safeid.policy.uri")))

        manifestPlaceholders["redirectScheme"] = redirectScheme
        manifestPlaceholders["redirectHost"] = redirectHost
        manifestPlaceholders["redirectPath"] = redirectPath
    }

    signingConfigs {
        val storePath = cfg("release.storeFile")
        if (storePath.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = cfg("release.storePassword")
                keyAlias = cfg("release.keyAlias")
                keyPassword = cfg("release.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Sem R8: app pessoal, e o pdfbox-android/BouncyCastle usam reflexão.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:stamps"))
    implementation(project(":core:signing"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.pdfbox.android)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
}
