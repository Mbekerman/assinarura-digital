// Testa na JVM o motor de PDF do app (app/src/main/kotlin/.../pdf/engine).
// O app usa o pdfbox-android (pacote com.tom_roush.pdfbox), que é um port do Apache PDFBox 2.0
// com a mesma API. Este módulo copia os fontes do motor trocando o pacote para org.apache.pdfbox
// e roda testes de geometria, renderização e assinatura sem precisar de Android.
plugins {
    id("org.jetbrains.kotlin.jvm") version libs.versions.kotlin.get()
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

val engineSrc = rootProject.layout.projectDirectory.dir("app/src/main/kotlin/br/com/bkm/assinapdf/pdf/engine")
val generated = layout.buildDirectory.dir("generated/pdfEngine")

val copyEngine by tasks.registering(Sync::class) {
    from(engineSrc)
    into(generated.map { it.dir("br/com/bkm/assinapdf/pdf/engine") })
    filter { line ->
        require(!line.trimStart().startsWith("import android")) { "o motor de PDF não pode usar APIs Android: $line" }
        line.replace("com.tom_roush.pdfbox", "org.apache.pdfbox").replace("com.tom_roush.fontbox", "org.apache.fontbox")
    }
}

sourceSets["main"].kotlin.srcDir(generated)
tasks.named("compileKotlin") { dependsOn(copyEngine) }

dependencies {
    implementation(project(":core:stamps"))
    implementation(project(":core:signing"))
    implementation(libs.pdfbox.jvm)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(testFixtures(project(":core:signing")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
    systemProperty("java.awt.headless", "true")
    systemProperty("assinapdf.out", layout.buildDirectory.dir("test-pdfs").get().asFile.absolutePath)
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
