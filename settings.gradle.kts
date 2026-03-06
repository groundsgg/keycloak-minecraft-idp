pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/groundsgg/*")
            credentials {
                // Local: gradle.properties (github.user / github.token)
                // CI: GITHUB_ACTOR / GITHUB_TOKEN set automatically by GitHub Actions
                username =
                    providers.gradleProperty("github.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password =
                    providers.gradleProperty("github.token").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
        gradlePluginPortal()
    }
}

rootProject.name = "keycloak-minecraft"
