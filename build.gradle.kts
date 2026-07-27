import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.GradleException
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("kapt") version "2.2.20"
    id("com.gradleup.shadow") version "9.2.2"
}

group = "dev.omnibridge"
version = "0.2.0"

repositories { mavenCentral() }

configurations.configureEach {
    exclude(group = "ch.qos.logback", module = "logback-classic")
}

val javalinVersion = "6.7.0"

dependencies {
    compileOnly("net.portswigger.burp.extensions:montoya-api:2026.7")
    implementation("io.javalin:javalin:$javalinVersion")
    implementation("io.javalin.community.openapi:javalin-openapi-plugin:$javalinVersion")
    implementation("io.javalin.community.openapi:javalin-swagger-plugin:$javalinVersion")
    kapt("io.javalin.community.openapi:openapi-annotation-processor:$javalinVersion")
    kapt("org.slf4j:slf4j-nop:1.7.36")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.0")
    implementation("com.google.re2j:re2j:1.8")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.14.6")
    testImplementation("net.portswigger.burp.extensions:montoya-api:2026.7")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test { useJUnitPlatform() }

tasks.withType<ShadowJar>().configureEach {
    archiveFileName.set("burp-omnibridge.jar")
    destinationDirectory.set(layout.projectDirectory.dir("output"))
    mergeServiceFiles()
    exclude("burp/api/montoya/**")
    manifest {
        attributes(
            "Implementation-Title" to "Burp OmniBridge",
            "Implementation-Version" to project.version
        )
    }
}

tasks.build { dependsOn(tasks.shadowJar) }

tasks.jar { enabled = false }

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val shadowJarTask = tasks.named<ShadowJar>("shadowJar")

val verifyShadowJar by tasks.registering {
    group = "verification"
    description = "Verifies that the BApp artifact is self-contained without bundling Montoya."
    dependsOn(shadowJarTask)
    val artifact = shadowJarTask.flatMap { it.archiveFile }
    inputs.file(artifact)

    doLast {
        ZipFile(artifact.get().asFile).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toSet()
            val requiredEntries = setOf(
                "dev/omnibridge/OmniBridgeExtension.class",
                "dev/omnibridge/mcp/McpHandler.class",
                "io/javalin/Javalin.class",
                "org/eclipse/jetty/server/Server.class",
                "com/fasterxml/jackson/databind/ObjectMapper.class",
                "openapi-plugin/openapi-default.json"
            )
            val missing = requiredEntries - entries
            if (missing.isNotEmpty()) {
                throw GradleException("Fat JAR is missing required entries: ${missing.sorted()}")
            }
            if (entries.none { it.startsWith("META-INF/resources/webjars/swagger-ui/") }) {
                throw GradleException("Fat JAR is missing the self-hosted Swagger UI assets")
            }
            if (entries.any { it.startsWith("burp/api/montoya/") }) {
                throw GradleException("Fat JAR must not bundle Burp-provided Montoya classes")
            }
        }
    }
}

tasks.check { dependsOn(verifyShadowJar) }
