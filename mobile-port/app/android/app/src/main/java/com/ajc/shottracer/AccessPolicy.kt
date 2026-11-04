package com.ajc.shottracer

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal const val THREE_DAYS_MS=3L*24*60*60*1000
internal fun accessAllowed(now: Long, trialUntil: Long, storeUntil: Long, clockValid: Boolean): Boolean =
    clockValid && (now<trialUntil || now<storeUntil)
internal fun extendedTrial(now: Long, until: Long): Long = Math.addExact(maxOf(now,until),THREE_DAYS_MS)
internal fun validTestGrant(device: String, audience: String, nonce: String, issued: Long, redeemBefore: Long,
                            seconds: Long, expectedDevice: String, platform: String, used: Set<String>, now: Long): Boolean =
    device==expectedDevice && audience==platform && nonce.isNotEmpty() && nonce !in used &&
        issued<=now+300 && now<redeemBefore && redeemBefore-issued in 1..7*86400 && seconds==3L*86400
internal fun verifyTestSignature(payload: ByteArray, signature: ByteArray, publicKey: String): Boolean = try {
    val key=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
    Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(payload); verify(signature) }
} catch (_: Exception) { false }
