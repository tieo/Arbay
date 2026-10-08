package io.github.tieo.arbay.signin

import io.github.tieo.arbay.SERVER_PORT
import io.github.tieo.arbay.chat.Chat
import io.github.tieo.arbay.module
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

private const val STAND_IN_PORT = 8097

/**
 * The server with its sign-in proxy pointed at [FakeSignInSite], for signing in from the web app in a
 * browser without a real Kleinanzeigen account. Started by ./gradlew :server:signInStandIn.
 */
fun main() {
    // ARBAY_SIGNIN_UPSTREAM=site leaves the proxy on the real site, to see its pages render through it.
    if (System.getenv("ARBAY_SIGNIN_UPSTREAM") != "site") {
        FakeSignInSite().start(STAND_IN_PORT)
        Chat.signInProxy.upstream = { "http://127.0.0.1:$STAND_IN_PORT" }
    }
    embeddedServer(Netty, port = SERVER_PORT, host = "127.0.0.1", module = Application::module).start(wait = true)
}
