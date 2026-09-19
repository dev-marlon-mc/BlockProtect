import org.gradle.api.tasks.compile.JavaCompile

plugins {
    java
}

group = "de.blockprotect.modules"
val moduleVersion = providers.gradleProperty("moduleVersion").orElse("1.0.0")
version = moduleVersion.get()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(project(":"))
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")
}

sourceSets {
    named("main") {
        java.srcDir(rootProject.file("src/main/java"))
        java.include("de/blockprotect/audit/SessionAuditModule.java")
        java.include("de/blockprotect/modules/sessions/SessionModule.java")
    }
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

tasks.jar {
    archiveBaseName = "BlockProtect-Sessions"
}

tasks.processResources {
    inputs.property("moduleVersion", moduleVersion)
    filesMatching("blockprotect-module.yml") {
        expand("moduleVersion" to moduleVersion.get())
    }
}
