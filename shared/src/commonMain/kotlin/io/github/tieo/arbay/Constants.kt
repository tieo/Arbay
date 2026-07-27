package io.github.tieo.arbay

const val SERVER_PORT = 8090

/** Fallback host, used only when no server address is configured. The real one is set per
 *  installation — baked in from the gitignored secret.properties (see AppSecrets) or entered in
 *  Settings — so nobody's network address lives in the source. */
const val DEFAULT_SERVER_HOST = "localhost"
