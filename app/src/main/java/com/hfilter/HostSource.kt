package com.hfilter

enum class SourceType {
    USER, BUILT_IN
}

data class HostSource(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val type: SourceType = SourceType.USER
)
