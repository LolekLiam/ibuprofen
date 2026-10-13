package com.ravijol1.ibuprofen

import com.ravijol1.ibuprofen.data.recoverUnreadablePreferences
import javax.crypto.AEADBadTagException
import org.junit.Assert.*
import org.junit.Test

class SecurePreferencesRecoveryTest {
    @Test fun healthyStorageIsPreserved() {
        val existing = Any()
        assertSame(existing, recoverUnreadablePreferences({ existing }, { true }, { error("Must not reset healthy storage") }))
    }

    @Test fun unreadableKeysetRecoversOnceAndUsesFreshEncryptedStorage() {
        var recoveries = 0
        assertEquals("fresh", recoverUnreadablePreferences({ throw AEADBadTagException() }, { true }, { recoveries++; "fresh" }))
        assertEquals(1, recoveries)
    }

    @Test fun lockedDeviceDoesNotDiscardCredentials() {
        val failure = AEADBadTagException()
        try {
            recoverUnreadablePreferences({ throw failure }, { false }, { error("Must not recover while locked") })
            fail("Expected original error")
        } catch (caught: AEADBadTagException) { assertSame(failure, caught) }
    }

    @Test fun unrelatedErrorsAreNotTreatedAsCorruption() {
        val failure = IllegalStateException("unrelated")
        try {
            recoverUnreadablePreferences({ throw failure }, { true }, { error("Must not reset") })
            fail("Expected original error")
        } catch (caught: IllegalStateException) { assertSame(failure, caught) }
    }
}
