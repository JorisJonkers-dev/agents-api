package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.common.web.GlobalExceptionHandler
import io.fabric8.kubernetes.api.model.Status
import io.fabric8.kubernetes.api.model.StatusCauseBuilder
import io.fabric8.kubernetes.api.model.StatusDetailsBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

class KubernetesExceptionHandlerTest {
    private var throwSupplier: () -> Nothing = { throw KubernetesClientException("boom") }
    private lateinit var mockMvc: MockMvc

    @RestController
    @RequestMapping("/k8s-test")
    inner class TestController {
        @GetMapping("/throw")
        fun throwIt(): Nothing = throwSupplier()
    }

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(TestController())
                // Both advices wired so we can verify the Kubernetes
                // handler wins by `@Order(HIGHEST_PRECEDENCE)`.
                .setControllerAdvice(KubernetesExceptionHandler(), GlobalExceptionHandler())
                .build()
    }

    @Test
    fun `forbidden status returns 502 with kubernetesCode and kubernetesReason`() {
        val status =
            Status().apply {
                code = 403
                reason = "Forbidden"
                message =
                    "pods is forbidden: User \"system:serviceaccount:agent-runtime:agents-api\" " +
                    "cannot create resource \"pods\" in API group \"\" in the namespace \"agent-runtime\""
            }
        throwSupplier = { throw KubernetesClientException(status) }

        mockMvc
            .perform(get("/k8s-test/throw"))
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.status").value(502))
            .andExpect(jsonPath("$.title").value("Kubernetes API Error"))
            .andExpect(jsonPath("$.kubernetesCode").value(403))
            .andExpect(jsonPath("$.kubernetesReason").value("Forbidden"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("pods is forbidden")))
            .andExpect(jsonPath("$.exception").value("io.fabric8.kubernetes.client.KubernetesClientException"))
    }

    @Test
    fun `invalid status surfaces field-level causes`() {
        val cause =
            StatusCauseBuilder()
                .withField("spec.containers[0].image")
                .withMessage("must be set")
                .withReason("FieldValueRequired")
                .build()
        val details = StatusDetailsBuilder().withCauses(cause).build()
        val status =
            Status().apply {
                code = 422
                reason = "Invalid"
                message = "Pod is invalid: spec.containers[0].image: Required value"
                this.details = details
            }
        throwSupplier = { throw KubernetesClientException(status) }

        mockMvc
            .perform(get("/k8s-test/throw"))
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.kubernetesCode").value(422))
            .andExpect(jsonPath("$.kubernetesReason").value("Invalid"))
            .andExpect(jsonPath("$.errors[0].field").value("spec.containers[0].image"))
            .andExpect(jsonPath("$.errors[0].message").value("must be set"))
    }

    @Test
    fun `exception without status falls back to exception message`() {
        throwSupplier = { throw KubernetesClientException("connection refused") }

        mockMvc
            .perform(get("/k8s-test/throw"))
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.title").value("Kubernetes API Error"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("connection refused")))
    }

    @Test
    fun `traceId from MDC is stamped onto the response`() {
        val status =
            Status().apply {
                code = 500
                reason = "InternalError"
                message = "etcd unavailable"
            }
        throwSupplier = { throw KubernetesClientException(status) }

        MDC.put("traceId", "kube-trace-1")
        try {
            mockMvc
                .perform(get("/k8s-test/throw"))
                .andExpect(status().isBadGateway)
                .andExpect(jsonPath("$.traceId").value("kube-trace-1"))
        } finally {
            MDC.clear()
        }
    }

    @Test
    fun `KubernetesClientException is preferred over the generic Exception handler`() {
        // If KubernetesExceptionHandler didn't have HIGHEST_PRECEDENCE,
        // GlobalExceptionHandler.handleUnexpected would catch this as
        // a 500. The 502 below proves the ordering holds.
        throwSupplier = { throw KubernetesClientException("anything") }

        mockMvc
            .perform(get("/k8s-test/throw"))
            .andExpect(status().isBadGateway)
    }
}
