package com.jorisjonkers.personalstack.agents.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Configuration
class SecurityConfig {
    @Bean
    fun xUserIdFilterRegistration(): FilterRegistrationBean<XUserIdFilter> =
        FilterRegistrationBean(XUserIdFilter()).apply {
            addUrlPatterns("/api/*")
            order = 1
        }

    @Bean
    fun githubInternalBearerFilterRegistration(
        props: AgentRuntimeProperties,
    ): FilterRegistrationBean<InternalBearerAuthFilter> =
        FilterRegistrationBean(InternalBearerAuthFilter(props.githubAppTokenBearer)).apply {
            addUrlPatterns("/api/v1/internal/github/*")
            order = 0
        }

    @Bean
    fun credentialInternalBearerFilterRegistration(
        props: AgentRuntimeProperties,
    ): FilterRegistrationBean<InternalBearerAuthFilter> =
        FilterRegistrationBean(InternalBearerAuthFilter(props.credentialIngestBearer)).apply {
            addUrlPatterns("/api/v1/internal/credentials")
            order = 0
        }
}

class XUserIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val userId = request.getHeader("X-User-Id")
        if (userId.isNullOrBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing X-User-Id header")
            return
        }
        filterChain.doFilter(request, response)
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        // /api/v1/internal/* carries no X-User-Id (it skips the edge
        // forward-auth); the bearer filter guards it instead.
        val path = request.servletPath
        return path.startsWith("/api/actuator") ||
            path.startsWith("/api/v1/health") ||
            path.startsWith("/api/v1/api-docs") ||
            path.startsWith("/api/v1/swagger-ui") ||
            path.startsWith("/api/v1/internal/")
    }
}

/**
 * Guards the in-cluster `/api/v1/internal/` endpoints with a shared
 * bearer. Fail-closed: when no bearer is configured every request is
 * rejected, so an unconfigured deployment never exposes these
 * endpoints unauthenticated. The comparison is constant-time.
 */
class InternalBearerAuthFilter(
    configuredBearer: String,
) : OncePerRequestFilter() {
    private val expected: ByteArray? =
        configuredBearer.trim().takeIf { it.isNotEmpty() }?.toByteArray(StandardCharsets.UTF_8)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (expected == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Internal endpoint not configured")
            return
        }
        val presented =
            request
                .getHeader("Authorization")
                ?.removePrefix("Bearer ")
                ?.trim()
                ?.toByteArray(StandardCharsets.UTF_8)
        if (presented == null || !MessageDigest.isEqual(expected, presented)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid bearer")
            return
        }
        filterChain.doFilter(request, response)
    }
}
