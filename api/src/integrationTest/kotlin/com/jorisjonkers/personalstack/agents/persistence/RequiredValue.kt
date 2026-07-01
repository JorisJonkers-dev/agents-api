package com.jorisjonkers.personalstack.agents.persistence

fun <T : Any> T?.required(): T = requireNotNull(this)
