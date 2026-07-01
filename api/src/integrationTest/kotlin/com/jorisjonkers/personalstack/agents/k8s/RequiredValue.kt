package com.jorisjonkers.personalstack.agents.k8s

fun <T : Any> T?.required(): T = requireNotNull(this)
