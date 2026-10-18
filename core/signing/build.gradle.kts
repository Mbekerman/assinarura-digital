plugins {
    id("org.jetbrains.kotlin.jvm") version libs.versions.kotlin.get()
    id("org.jetbrains.kotlin.plugin.serialization") version libs.versions.kotlin.get()
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.bouncycastle.bcprov)
    api(libs.bouncycastle.bcpkix)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testFixturesApi(libs.bouncycastle.bcprov)
    testFixturesApi(libs.bouncycastle.bcpkix)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
