plugins {
    id("gg.grounds.kotlin-conventions") version "0.3.2"
    id("com.gradleup.shadow") version "8.3.5"
}

// Override JVM 24 from base-conventions — Keycloak 26.x runs on Java 21
kotlin { jvmToolchain(21) }

version = "1.1.0"

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
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.assemble { dependsOn(tasks.shadowJar) }
