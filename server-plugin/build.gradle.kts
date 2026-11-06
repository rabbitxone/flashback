plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
}

group = "com.moulberry.flashback"
version = "0.1.0"

repositories {
    // Paper's repository also mirrors Maven Central
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle("26.3.build.244-stable")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
