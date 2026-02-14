package com.hfilter.model

import java.util.UUID

data class HostSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val type: SourceType = SourceType.USER
)

enum class SourceType {
    BUILT_IN, USER
}
