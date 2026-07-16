package pl.blizinski.tasksync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * True if the device currently has a network connection with internet access. Used by
 * [SyncEngine]'s `isOnline` check to skip a doomed sync attempt instead of letting it fail with
 * an [java.io.IOException] from the underlying HTTP client.
 */
fun isNetworkAvailable(context: Context): Boolean {
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
