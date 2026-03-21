package io.github.tieo.arbay

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform