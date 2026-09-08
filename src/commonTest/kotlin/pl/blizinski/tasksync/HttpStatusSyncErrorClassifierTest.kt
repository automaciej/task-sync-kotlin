package pl.blizinski.tasksync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpStatusSyncErrorClassifierTest {

    private class ApiException(val status: Int?) : Exception()

    private val classifier = HttpStatusSyncErrorClassifier(
        statusOf = { (it as? ApiException)?.status },
    )

    @Test
    fun status401_isAuthFailed() {
        assertEquals(SyncErrorKind.AUTH_FAILED, classifier.classifySpecial(ApiException(401)))
    }

    @Test
    fun otherStatuses_areNotSpecial() {
        assertNull(classifier.classifySpecial(ApiException(500)))
        assertNull(classifier.classifySpecial(ApiException(403)))
        assertNull(classifier.classifySpecial(ApiException(null)))
        assertNull(classifier.classifySpecial(RuntimeException("network down")))
    }

    @Test
    fun httpStatus_isExtractedVerbatim() {
        assertEquals(429, classifier.httpStatus(ApiException(429)))
        assertNull(classifier.httpStatus(RuntimeException()))
    }

    @Test
    fun extractConsentIntent_isAlwaysNull() {
        assertNull(classifier.extractConsentIntent(ApiException(401)))
    }

    @Test
    fun extraSpecial_runsFirst_andWins() {
        val reauth = IllegalStateException("reauth")
        val withExtra = HttpStatusSyncErrorClassifier(
            statusOf = { (it as? ApiException)?.status },
            extraSpecial = { if (it is IllegalStateException) SyncErrorKind.AUTH_FAILED else null },
        )
        assertEquals(SyncErrorKind.AUTH_FAILED, withExtra.classifySpecial(reauth))
        assertNull(withExtra.classifySpecial(ApiException(500)))
    }
}
