package com.obscura.kit.crypto

import java.util.Base64

fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)
fun String.fromBase64(): ByteArray = Base64.getDecoder().decode(this)
fun String.fromBase64Url(): ByteArray = Base64.getUrlDecoder().decode(this)
