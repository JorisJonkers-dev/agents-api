package com.jorisjonkers.personalstack.agents

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication(scanBasePackages = ["com.jorisjonkers.personalstack"])
class AgentsApiApplication

fun main(args: Array<String>) {
    SpringApplication.run(arrayOf(AgentsApiApplication::class.java), args)
}
