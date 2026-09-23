package io.github.tieo.arbay.testing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine

/**
 * A client for crawlers under test that only parse. Any request fails the test instead of
 * reaching the network, so a parser test cannot quietly depend on a market being up.
 */
fun offlineClient(): HttpClient = HttpClient(MockEngine { request ->
    error("A parser test made a request to ${request.url}")
})
