package com.ajc.shottracer

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class AccessPolicyTest {
    @Test fun trialEndsExactlyAfterThreeDaysWithoutCreatingPaidAccess() {
        val start=1_000_000L;val until=start+THREE_DAYS_MS
        assertTrue(accessAllowed(until-1,until,0,true))
        assertFalse(accessAllowed(until,until,0,true))
        assertTrue(accessAllowed(until,until,until+1000,true))
        assertFalse(accessAllowed(until+1000,until,until+1000,true))
    }
    @Test fun rollbackNeverGrantsLocalAccess() { assertFalse(accessAllowed(0,THREE_DAYS_MS,THREE_DAYS_MS,false)) }
    @Test fun eachUniqueGrantAddsThreeDaysToTheRemainingTrialOrNow() {
        assertEquals(1000+THREE_DAYS_MS,extendedTrial(1000,999))
        assertEquals(1000+2*THREE_DAYS_MS,extendedTrial(1000,1000+THREE_DAYS_MS))
    }
    @Test fun codeIsBoundToDevicePlatformWindowDurationAndSingleUse() {
        fun valid(device:String="A",platform:String="android",nonce:String="one",issued:Long=900,limit:Long=1100,seconds:Long=259200,used:Set<String> = emptySet()) =
            validTestGrant(device,platform,nonce,issued,limit,seconds,"A","android",used,1000)
        assertTrue(valid())
        assertFalse(valid(device="B"));assertFalse(valid(platform="ios"));assertFalse(valid(nonce=""))
        assertFalse(valid(used=setOf("one")));assertFalse(valid(limit=1000));assertFalse(valid(issued=1301))
        assertFalse(valid(seconds=259201));assertFalse(valid(limit=900+7*86400+1))
    }
    @Test fun signaturesRejectModifiedPayloadsAndOtherIssuerKeys() {
        val generator=KeyPairGenerator.getInstance("EC");generator.initialize(256)
        val pair=generator.generateKeyPair();val other=generator.generateKeyPair()
        val payload="tester device A, three days".toByteArray()
        val signature=Signature.getInstance("SHA256withECDSA").run {initSign(pair.private);update(payload);sign()}
        val public=Base64.getEncoder().encodeToString(pair.public.encoded)
        assertTrue(verifyTestSignature(payload,signature,public))
        assertFalse(verifyTestSignature("modified".toByteArray(),signature,public))
        assertFalse(verifyTestSignature(payload,signature,Base64.getEncoder().encodeToString(other.public.encoded)))
        assertFalse(verifyTestSignature(payload,byteArrayOf(1,2,3),public))
    }
}
