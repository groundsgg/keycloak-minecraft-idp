import org.gradle.api.publish.maven.MavenPublication

plugins {
    kotlin("jvm") version "2.3.20"
    id("com.diffplug.spotless") version "8.4.0"
    id("com.gradleup.shadow") version "8.3.5"
    `maven-publish`
}

group = "gg.grounds"

val versionOverride = project.findProperty("versionOverride") as? String

version = versionOverride ?: "local-SNAPSHOT"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

val keycloakVersion = "26.5.7"

dependencies {
    compileOnly(platform("org.keycloak:keycloak-parent:$keycloakVersion"))
    compileOnly("org.keycloak:keycloak-server-spi")
    compileOnly("org.keycloak:keycloak-server-spi-private")
    compileOnly("org.keycloak:keycloak-services")
    compileOnly("com.fasterxml.jackson.core:jackson-databind")
    compileOnly("org.jboss.logging:jboss-logging")
    testImplementation(platform("org.keycloak:keycloak-parent:$keycloakVersion"))
    testImplementation("org.keycloak:keycloak-server-spi")
    testImplementation("org.keycloak:keycloak-server-spi-private")
    testImplementation("org.keycloak:keycloak-services")
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("org.jboss.logging:jboss-logging")
    testImplementation(kotlin("test"))
}

spotless {
    kotlin {
        ktfmt().googleStyle().configure {
            it.setBlockIndent(4)
            it.setContinuationIndent(4)
        }
        targetExclude("**/build/**")
    }
    kotlinGradle {
        ktfmt().googleStyle().configure {
            it.setBlockIndent(4)
            it.setContinuationIndent(4)
        }
        targetExclude("**/build/**")
    }
}

tasks.shadowJar {
    archiveBaseName.set("keycloak-minecraft-idp")
    archiveClassifier.set("")
    archiveVersion.set("")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

publishing {
    publications { create<MavenPublication>("java") { artifact(tasks.shadowJar) } }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/groundsgg/${rootProject.name}")
            credentials {
                username = providers.gradleProperty("github.user").orNull
                password = providers.gradleProperty("github.token").orNull
            }
        }
    }
}

tasks.assemble { dependsOn(tasks.shadowJar) }

tasks.test { useJUnitPlatform() }
