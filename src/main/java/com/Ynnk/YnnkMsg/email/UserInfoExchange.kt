package com.Ynnk.YnnkMsg.email

import com.google.gson.annotations.SerializedName

data class UserInfoExchange(
    @SerializedName("has_avatar")
    val hasAvatar: Boolean = false,
    @SerializedName("secure_email")
    var secureEmail: String? = null,
    @SerializedName("exclusive_primary_email")
    var exclusivePrimaryEmail: Boolean = false,
    @SerializedName("public_name")
    var publicName: String? = null
)
