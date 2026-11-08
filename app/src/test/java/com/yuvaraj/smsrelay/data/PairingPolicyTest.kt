package com.yuvaraj.smsrelay.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPolicyTest {
    @Test fun firstReceiverCanClaimAnUnpairedSender() {
        assertTrue(PairingPolicy.canClaim(null, "receiver-1", "sender-1"))
    }

    @Test fun anotherReceiverCannotReplaceTheExistingReceiver() {
        assertFalse(PairingPolicy.canClaim("receiver-1", "receiver-2", "sender-1"))
    }

    @Test fun sameReceiverCanReconnectToItsExistingPairing() {
        assertTrue(PairingPolicy.canClaim("receiver-1", "receiver-1", "sender-1"))
    }

    @Test fun senderCannotPairToItself() {
        assertFalse(PairingPolicy.canClaim(null, "sender-1", "sender-1"))
    }

    @Test fun onlyOwnerCanDisconnectAnExistingReceiver() {
        assertTrue(PairingPolicy.canDisconnect("sender-1", "sender-1", "receiver-1"))
        assertFalse(PairingPolicy.canDisconnect("sender-1", "receiver-1", "receiver-1"))
        assertFalse(PairingPolicy.canDisconnect("sender-1", "sender-1", null))
    }
}
