package com.github.libretube.api.ltsync.obj

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class SubscriptionGroup(
    @SerialName(value = "id")
    val id: String,
    @SerialName(value = "title")
    val title: String,
    @SerialName(value = "local_id")
    val localId: String? = null,
    @SerialName(value = "bg_color")
    val backgroundColor: String? = null,
    @SerialName(value = "text_color")
    val textColor: String? = null
)
