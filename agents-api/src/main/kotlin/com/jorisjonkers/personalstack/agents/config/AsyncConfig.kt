package com.jorisjonkers.personalstack.agents.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

@Configuration
@EnableAsync
@EnableScheduling
class AsyncConfig {
    /**
     * Fallback ObjectMapper for components that need JSON but are
     * out of the Spring-MVC auto-configuration scope (the WebSocket
     * starter doesn't bring JacksonAutoConfiguration with it on its
     * own under @SpringBootTest in this module). Marked
     * `@ConditionalOnMissingBean` semantics via @Qualifier so the
     * Boot-provided one wins when both exist.
     */
    @Bean
    @Qualifier("agentsApiObjectMapper")
    fun agentsApiObjectMapper(): ObjectMapper = jacksonObjectMapper()

    @Bean(name = ["chatStreamExecutor"])
    fun chatStreamExecutor(): Executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = CHAT_STREAM_CORE_POOL_SIZE
            maxPoolSize = CHAT_STREAM_MAX_POOL_SIZE
            queueCapacity = CHAT_STREAM_QUEUE_CAPACITY
            setThreadNamePrefix("chat-stream-")
            setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
            initialize()
        }

    @Bean(name = ["sessionStatusExecutor"])
    fun sessionStatusExecutor(): Executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = SESSION_STATUS_CORE_POOL_SIZE
            maxPoolSize = SESSION_STATUS_MAX_POOL_SIZE
            queueCapacity = SESSION_STATUS_QUEUE_CAPACITY
            setThreadNamePrefix("session-status-")
            setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
            initialize()
        }

    private companion object {
        private const val CHAT_STREAM_CORE_POOL_SIZE = 2
        private const val CHAT_STREAM_MAX_POOL_SIZE = 8
        private const val CHAT_STREAM_QUEUE_CAPACITY = 50
        private const val SESSION_STATUS_CORE_POOL_SIZE = 1
        private const val SESSION_STATUS_MAX_POOL_SIZE = 4
        private const val SESSION_STATUS_QUEUE_CAPACITY = 250
    }
}
