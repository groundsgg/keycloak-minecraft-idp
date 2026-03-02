plugins {
    kotlin("jvm") version "2.1.10"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "gg.grounds"
version = "1.1.0"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

val keycloakVersion = "26.5.4"

dependencies {
    compileOnly(platform("org.keycloak:keycloak-parent:$keycloakVersion"))
    compileOnly("org.keycloak:keycloak-server-spi")
    compileOnly("org.keycloak:keycloak-server-spi-private")
    compileOnly("org.keycloak:keycloak-services")
    compileOnly("com.fasterxml.jackson.core:jackson-databind")
    compileOnly("org.jboss.logging:jboss-logging")
}

tasks.shadowJar {
    archiveBaseName.set("keycloak-minecraft")
    archiveClassifier.set("")
    archiveVersion.set("")
    // Exclude signature files that can cause issues in fat jars
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
