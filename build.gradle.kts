import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    base
}

description = "Kotlin/Spring API service for agent workspaces, sessions, credentials, and runner orchestration."

group = "dev.jorisjonkers"
version =
    providers
        .gradleProperty("artifactVersion")
        .orElse(
            providers.environmentVariable("GITHUB_REF_NAME").map { ref ->
                if (ref.startsWith("v")) ref.removePrefix("v") else "0.16.0-SNAPSHOT"
            },
        ).orElse("0.16.0-SNAPSHOT")
        .get()

subprojects {
    group = rootProject.group
    version = rootProject.version

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension>("publishing") {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    licenses {
                        license {
                            name.set("Joris Jonkers Proprietary Source-Available License 1.0")
                            url.set("https://github.com/JorisJonkers-dev/agents-api/blob/main/LICENSE")
                            distribution.set("repo")
                            comments.set("SPDX-License-Identifier: LicenseRef-JorisJonkers-Proprietary-1.0")
                        }
                    }
                }
            }
        }
    }
}
