package com.hfilter.model

import java.util.UUID

data class AppPredefinition(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val packageNames: List<String>,
    val blockedDomains: Set<String> = emptySet(),
    val allowedDomains: Set<String> = emptySet(),
    val enabled: Boolean = true
)
