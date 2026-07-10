plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.jorisjonkers.spring)
    alias(libs.plugins.jorisjonkers.detekt)
    alias(libs.plugins.jorisjonkers.ktlint)
    alias(libs.plugins.jorisjonkers.testing)
    alias(libs.plugins.jorisjonkers.jooq.codegen)
}

val prepareJooqMigrations by tasks.registering(Sync::class) {
    from("src/main/resources/db/migration") {
        exclude("V21__rebrand_agent_runtime_image.sql")
        exclude("V22__agent_setup_v018.sql")
    }
    into(layout.buildDirectory.dir("jooq-migration"))
}

jooqCodegen {
    schemaName.set("PUBLIC")
    packageName.set("com.jorisjonkers.personalstack.agents.jooq")
    migrationLocations.set(listOf("filesystem:build/jooq-migration"))
}

tasks.named("generateJooq") {
    dependsOn(prepareJooqMigrations)
}

tasks
    .matching {
        it.name == "runKtlintCheckOverMainSourceSet" ||
            it.name == "runKtlintFormatOverMainSourceSet"
    }.configureEach {
        dependsOn(tasks.named("generateJooq"))
    }

dependencies {
    implementation(libs.kotlin.commons.command)
    implementation(libs.kotlin.commons.crac)
    implementation(libs.kotlin.commons.events)
    implementation(libs.kotlin.commons.exceptions)
    implementation(libs.kotlin.commons.observability)
    implementation(libs.kotlin.commons.timing)
    implementation(libs.kotlin.commons.vault)
    implementation(libs.kotlin.commons.web)
    testImplementation(libs.kotlin.commons.archunit.test)
    testImplementation(libs.kotlin.commons.test.support)
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    // See auth-api build.gradle.kts — needed for the
    // ApplicationTracingAspect in kotlin-commons-observability to take effect.
    // Spring Boot 4 doesn't ship a starter-aop shortcut.
    implementation("org.springframework:spring-aop")
    implementation("org.aspectj:aspectjweaver:1.9.25.1")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.jooq:jooq")
    runtimeOnly("org.postgresql:postgresql")
    // Tracing runtime jars. kotlin-commons-timing's TimingAutoConfiguration becomes
    // active when these are on the classpath: spans flow to Alloy → Tempo
    // and MDC traceId/spanId start populating in the JSON log lines.
    runtimeOnly("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
    // k3s lets Fabric8AgentRunnerOrchestratorIntegrationTest exercise
    // the orchestrator through a real Kubernetes API — the seam where
    // PR #372 (PVC patch-verb missing on the production Role) failed
    // in production despite green unit tests. The negative case in
    // that test locks the RBAC contract in.
    testImplementation("org.testcontainers:testcontainers-k3s")
    // fabric8 KubernetesClient — drives the per-workspace runner Pod
    // lifecycle. Server-side apply, no informer cache (one-shot CRUD
    // is enough), pulled in with the kubernetes-client-bom to keep
    // model + httpclient versions aligned.
    implementation(platform("io.fabric8:kubernetes-client-bom:7.7.0"))
    implementation("io.fabric8:kubernetes-client")
    testImplementation("io.fabric8:kubernetes-server-mock")
}

// agent-runner orchestration shells out to Kubernetes and HTTP from
// classes that only carry signal under an integration cluster; the
// 80 % jacoco bar stays honest for the classes that are actually
// unit-testable by registering the IO-bound shells with the shared
// `jacocoExclusionPatterns` extension from the testing convention.
//
// `VaultDeployKeyStore`, `KnowledgeMcpClient`, `LightRagClient` are
// `@ConditionalOnProperty` adapters that only wire when their
// upstream is available; unit tests can't reach them and the
// integration tier doesn't yet stand up a Vault / knowledge-api
// fixture for the agents-api integration test suite (the
// Fabric8 orchestrator integration test covers the k8s side). They
// follow the same IO-bound exclusion treatment as
// `HttpAgentGatewayClient`. The trailing `*.class` (rather than just
// `.class`) sweeps Kotlin-generated `Outer$Inner.class` companions —
// the HTTP shells declare request/response DTOs inline and the
// companions would otherwise drag the package into the report on
// their own.
@Suppress("UNCHECKED_CAST")
(extensions.getByName("jacocoExclusionPatterns") as ListProperty<String>).addAll(
    "**/infrastructure/k8s/**",
    "**/infrastructure/integration/HttpAgentGatewayClient*.class",
    "**/infrastructure/integration/VaultDeployKeyStore*.class",
    "**/infrastructure/integration/KnowledgeMcpClient*.class",
    "**/infrastructure/integration/LightRagClient*.class",
    "**/infrastructure/ws/**",
)

// The OpenAPI contract is pinned to `client-spec/openapi/agents-api.json`
// (committed). The `contract-export` JUnit tag identifies the single
// springdoc MVC slice test that hits `/api/v1/api-docs` without booting the
// app server and writes the spec to disk. The default `integrationTest` task
// skips that tag; `exportOpenApiSpec` runs only that tag for the contract
// drift gate and the generated clients.
tasks.named<Test>("integrationTest") {
    useJUnitPlatform {
        includeTags("integration")
        excludeTags("contract-export")
    }
    systemProperty("springdoc.api-docs.enabled", "true")
    systemProperty("springdoc.swagger-ui.enabled", "false")
}

tasks.register<Test>("exportOpenApiSpec") {
    description = "Exports the OpenAPI spec to client-spec/openapi/agents-api.json from a springdoc MVC slice"
    group = "documentation"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
        isEnabled = false
    }
    useJUnitPlatform {
        includeTags("contract-export")
    }
    systemProperty(
        "openapi.spec.output",
        rootProject.layout.projectDirectory
            .file("client-spec/openapi/agents-api.json")
            .asFile.absolutePath,
    )
    systemProperty("springdoc.api-docs.enabled", "true")
    systemProperty("springdoc.swagger-ui.enabled", "false")
    // Always re-run: the spec is derived from the live springdoc output,
    // so caching past runs would defeat the drift gate. The CI workflow
    // diffs the freshly-written file against the committed copy.
    outputs.upToDateWhen { false }
}

tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    executionData.setFrom(
        layout.buildDirectory.file("jacoco/test.exec"),
        layout.buildDirectory.file("jacoco/integrationTest.exec"),
    )
}
