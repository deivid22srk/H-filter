package com.hfilter.model

import com.google.gson.annotations.SerializedName

data class FilterResponse(
    @SerializedName("steven_black_hosts")
    val stevenBlackHosts: List<FilterItem> = emptyList(),
    @SerializedName("one_dm_host")
    val oneDmHost: List<FilterItem> = emptyList(),
    val filters: List<FilterItem> = emptyList()
)

data class FilterItem(
    val name: String,
    val link: String
)
