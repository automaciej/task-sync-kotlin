package pl.blizinski.tasksync

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectivityErrorTest {

    @Test
    fun unknownHost_isConnectivity() {
        assertTrue(isConnectivityException(UnknownHostException("Unable to resolve host \"tasks.googleapis.com\"")))
    }

    @Test
    fun connectAndTimeoutAndReset_areConnectivity() {
        assertTrue(isConnectivityException(ConnectException("Failed to connect")))
        assertTrue(isConnectivityException(SocketTimeoutException("timeout")))
        assertTrue(isConnectivityException(SSLException("Connection reset by peer")))
    }

    @Test
    fun connectivityCauseWrappedInGenericException_isConnectivity() {
        val wrapped = RuntimeException("sync failed", IOException("io", UnknownHostException("no dns")))
        assertTrue(isConnectivityException(wrapped))
    }

    @Test
    fun unrelatedException_isNotConnectivity() {
        assertFalse(isConnectivityException(IllegalStateException("boom")))
        assertFalse(isConnectivityException(IOException("disk full")))
    }

    @Test
    fun cyclicCauseChain_terminates() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(isConnectivityException(a))
    }
}
