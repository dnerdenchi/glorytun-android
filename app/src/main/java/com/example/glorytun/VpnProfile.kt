package com.example.glorytun

import java.util.UUID

data class VpnProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val ip: String,
    val port: String,
    val secret: String,
    val allowInsecureCertificate: Boolean = MqvpnConfigFactory.DEFAULT_ALLOW_INSECURE
)
