import org.gradle.api.tasks.Copy
import org.gradle.jvm.tasks.Jar
import java.security.MessageDigest

plugins {
    java
}

group = "de.blockprotect"
version = "0.2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName = "BlockProtect"
    // These classes are compiled from the shared source tree but are packaged
    // only into the independently reloadable module jars below.
    exclude("de/blockprotect/audit/BlockAuditModule*.class")
    exclude("de/blockprotect/audit/ContainerAuditModule*.class")
    exclude("de/blockprotect/audit/EntityAuditModule*.class")
    exclude("de/blockprotect/audit/InteractionAuditModule*.class")
    exclude("de/blockprotect/audit/SessionAuditModule*.class")
}

val installTestServer = tasks.register<Copy>("installTestServer") {
    dependsOn(tasks.jar)
    dependsOn(":modules:audit-suite:jar", ":modules:session-audit:jar")
    from(tasks.jar)
    into(layout.projectDirectory.dir("test-server/plugins"))
    rename { "BlockProtect.jar" }
}

fun sha256(file: java.io.File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(16 * 1024)
        var read = input.read(buffer)
        while (read >= 0) {
            if (read > 0) {
                digest.update(buffer, 0, read)
            }
            read = input.read(buffer)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val installTestServerModules = tasks.register("installTestServerModules") {
    dependsOn(":modules:audit-suite:jar", ":modules:session-audit:jar")
    doLast {
        val moduleDir = layout.projectDirectory.dir("test-server/plugins/BlockProtect/modules").asFile
        moduleDir.mkdirs()
        val moduleJars = listOf(
            project(":modules:audit-suite").tasks.named<Jar>("jar").get().archiveFile.get().asFile,
            project(":modules:session-audit").tasks.named<Jar>("jar").get().archiveFile.get().asFile
        )
        moduleJars.forEach { source ->
            val id = when {
                source.name.startsWith("BlockProtect-Audit-") -> "audit"
                source.name.startsWith("BlockProtect-Sessions-") -> "sessions"
                else -> error("Unbekanntes Modul-JAR: ${source.name}")
            }
            val target = moduleDir.resolve("$id.jar")
            source.copyTo(target, overwrite = true)
        }
    }
}

val prepareTestServerModuleUpdate = tasks.register("prepareTestServerModuleUpdate") {
    dependsOn(":modules:audit-suite:jar", ":modules:session-audit:jar")
    doLast {
        val updateDir = layout.projectDirectory.dir("test-server/plugins/BlockProtect/updates").asFile
        updateDir.mkdirs()
        val moduleJars = listOf(
            project(":modules:audit-suite").tasks.named<Jar>("jar").get().archiveFile.get().asFile,
            project(":modules:session-audit").tasks.named<Jar>("jar").get().archiveFile.get().asFile
        )
        moduleJars.forEach { source ->
            val target = updateDir.resolve(source.name)
            source.copyTo(target, overwrite = true)
            updateDir.resolve("${source.name}.sha256").writeText("${sha256(target)}  ${target.name}\n")
            logger.lifecycle("Lokales Modul-Update vorbereitet: ${target}")
        }
    }
}

tasks.named("build") {
    finalizedBy(installTestServer, installTestServerModules)
}
