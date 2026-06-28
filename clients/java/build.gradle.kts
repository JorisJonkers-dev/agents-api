import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.jorisjonkers.openapi.client)
    `maven-publish`
}

openApiClient {
    specPath.set("client-spec/openapi/agents-api.json")
    apiPackage.set("dev.jorisjonkers.agents.client.api")
    modelPackage.set("dev.jorisjonkers.agents.client.model")
    packageName.set("dev.jorisjonkers.agents.client")
    generatorName.set("java")
    library.set("restclient")
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.named("sourcesJar") {
    dependsOn(tasks.named("generate"))
}

tasks.named("javadocJar") {
    dependsOn(tasks.named("generate"))
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "dev.jorisjonkers"
            artifactId = "agents-api-client-java"
            version = project.version.toString()
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/JorisJonkers-dev/agents-api")
            credentials {
                username =
                    providers
                        .gradleProperty("gpr.user")
                        .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                        .orNull
                password =
                    providers
                        .gradleProperty("gpr.token")
                        .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                        .orNull
            }
        }
    }
}
