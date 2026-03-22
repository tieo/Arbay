package io.github.tieo.arbay

expect fun defaultServerHost(): String

fun defaultServerUrl(): String = "http://${defaultServerHost()}:$SERVER_PORT"
