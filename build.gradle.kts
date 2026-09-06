import org.gradle.api.tasks.Copy

plugins {
    java
}

group = "de.blockprotect"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
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
}

val installTestServer = tasks.register<Copy>("installTestServer") {
    dependsOn(tasks.jar)
    from(tasks.jar)
    into(layout.projectDirectory.dir("test-server/plugins"))
    rename { "BlockProtect.jar" }
}

tasks.named("build") {
    finalizedBy(installTestServer)
}
