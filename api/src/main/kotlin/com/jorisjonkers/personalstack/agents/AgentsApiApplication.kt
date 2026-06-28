package com.jorisjonkers.personalstack.agents

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.jorisjonkers.personalstack"])
class AgentsApiApplication

fun main(args: Array<String>) {
    runApplication<AgentsApiApplication>(*args)
}
