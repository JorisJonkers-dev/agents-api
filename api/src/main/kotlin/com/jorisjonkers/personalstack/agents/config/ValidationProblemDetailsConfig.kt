package com.jorisjonkers.personalstack.agents.config

import com.jorisjonkers.personalstack.common.web.WebUtilitiesProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus

@Configuration(proxyBeanMethods = false)
class ValidationProblemDetailsConfig {
    @Bean
    fun validationProblemDetailsProperties(): WebUtilitiesProperties.ProblemDetailsProperties =
        WebUtilitiesProperties.ProblemDetailsProperties(
            validationStatus = HttpStatus.UNPROCESSABLE_ENTITY.value(),
        )
}
