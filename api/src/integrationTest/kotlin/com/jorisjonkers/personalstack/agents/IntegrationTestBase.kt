package com.jorisjonkers.personalstack.agents

import org.junit.jupiter.api.Tag
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.utility.DockerImageName

@Tag("integration")
@SpringBootTest
@Testcontainers
interface IntegrationTestBase {
    companion object {
        private const val VALKEY_PORT = 6379

        private val postgres =
            PostgreSQLContainer("postgres:17-alpine").apply {
                withDatabaseName("agents_db")
                withUsername("agents_user")
                withPassword("agents_pass")
            }

        private val valkey =
            GenericContainer<Nothing>(DockerImageName.parse("valkey/valkey:7-alpine")).apply {
                withExposedPorts(VALKEY_PORT)
                waitingFor(Wait.forLogMessage(".*Ready to accept connections tcp.*\\n", 1))
            }

        private val rabbitmq = RabbitMQContainer("rabbitmq:3-management-alpine")

        init {
            postgres.start()
            valkey.start()
            rabbitmq.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { valkey.host }
            registry.add("spring.data.redis.port") { valkey.getMappedPort(VALKEY_PORT).toString() }
            registry.add("spring.rabbitmq.host") { rabbitmq.host }
            registry.add("spring.rabbitmq.port") { rabbitmq.amqpPort.toString() }
        }
    }
}
