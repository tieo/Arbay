package io.github.tieo.arbay.routes

import io.github.tieo.arbay.signin.SignInProxy
import io.ktor.server.routing.*

/** Kleinanzeigen's sign-in pages through the proxy: every method and every path under the prefix goes to it. */
fun Route.signInProxyRoutes(proxy: SignInProxy) {
    route("/signin-proxy/{...}") { handle { proxy.handle(call) } }
}
