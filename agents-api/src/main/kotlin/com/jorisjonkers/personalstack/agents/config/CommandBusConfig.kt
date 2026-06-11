package com.jorisjonkers.personalstack.agents.config

import com.jorisjonkers.personalstack.common.command.Command
import com.jorisjonkers.personalstack.common.command.CommandBus
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.aop.support.AopUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.reflect.KClass
import kotlin.reflect.full.allSupertypes

@Configuration
class CommandBusConfig {
    @Bean
    fun commandBus(handlers: List<CommandHandler<*>>): CommandBus = SpringCommandBus(handlers)
}

class SpringCommandBus(
    handlers: List<CommandHandler<*>>,
) : CommandBus {
    private val handlerMap: Map<KClass<*>, CommandHandler<*>> =
        buildMap {
            for (handler in handlers) {
                val commandType =
                    resolveCommandType(handler)
                        ?: error("Cannot determine command type for ${handler::class.simpleName}")
                put(commandType, handler)
            }
        }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Command> dispatch(command: T) {
        val handler =
            handlerMap[command::class] as? CommandHandler<T>
                ?: error("No handler registered for ${command::class.simpleName}")
        handler.handle(command)
    }

    // See auth-api's CommandBusConfig — same rationale: unwrap CGLIB
    // proxies and walk the full supertype graph so the generic
    // CommandHandler<T> parameter is found even when AOP wraps the bean.
    private fun resolveCommandType(handler: CommandHandler<*>): KClass<*>? =
        AopUtils
            .getTargetClass(handler)
            .kotlin
            .allSupertypes
            .firstOrNull { it.classifier == CommandHandler::class }
            ?.arguments
            ?.firstOrNull()
            ?.type
            ?.classifier as? KClass<*>
}
