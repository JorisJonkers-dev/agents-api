package com.jorisjonkers.personalstack.agents.config

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
@EnableConfigurationProperties(AgentRuntimeProperties::class, RagProperties::class, ChatGenerationProperties::class)
class AgentRuntimeConfig {
    /**
     * In-cluster mode by default — KubernetesClientBuilder picks up
     * the SA token + CA from /var/run/secrets when running in a Pod
     * and falls back to ~/.kube/config locally for tests.
     */
    @Bean
    fun kubernetesClient(): KubernetesClient = KubernetesClientBuilder().build()

    @Bean
    fun agentGatewayRestClient(props: AgentRuntimeProperties): RestClient {
        val factory =
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofMillis(props.gatewayConnectTimeoutMs))
                setReadTimeout(Duration.ofMillis(props.gatewayReadTimeoutMs))
            }
        return RestClient.builder().requestFactory(factory).build()
    }
}
