package app.capgo.plugin.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthManagerTest {

    @Test
    fun `recoverable cursor messages include explicit token errors`() {
        assertTrue(
            HealthManager.isRecoverableChangesCursorMessage(
                "Changes token has expired and must be refreshed."
            )
        )
    }

    @Test
    fun `recoverable cursor messages include protobuf invalid tag errors`() {
        assertTrue(
            HealthManager.isRecoverableChangesCursorMessage(
                "Protocol message contained an invalid tag (zero)."
            )
        )
    }

    @Test
    fun `non cursor messages stay non recoverable`() {
        assertFalse(
            HealthManager.isRecoverableChangesCursorMessage(
                "Permission denied for heart rate read."
            )
        )
    }
}
