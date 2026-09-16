package org.qinda.deck.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A deck seen on the network. */
data class Found(
    val host: String,
    val port: Int,
    val name: String,
)

/**
 * mDNS discovery of `_qindadeck._tcp`.
 *
 * Emits as decks are resolved rather than collecting a list, so the connect
 * screen can show the first one the instant it appears. The resolve API split in
 * API 34, so both paths are here.
 */
class Discovery(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun decks(): Flow<Found> = callbackFlow {
        val resolved = HashSet<String>()

        fun emit(info: NsdServiceInfo) {
            val host = info.host?.hostAddress ?: return
            // A deck can be advertised on several interfaces; one entry is enough.
            if (!resolved.add(host)) return
            trySend(Found(host, info.port, info.serviceName ?: "QindaDeck"))
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    nsd.registerServiceInfoCallback(
                        info,
                        { it.run() },
                        object : NsdManager.ServiceInfoCallback {
                            override fun onServiceInfoCallbackRegistrationFailed(code: Int) {}
                            override fun onServiceUpdated(updated: NsdServiceInfo) = emit(updated)
                            override fun onServiceLost() {}
                            override fun onServiceInfoCallbackUnregistered() {}
                        },
                    )
                } else {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                            Log.w(TAG, "resolve failed for ${i.serviceName}: $code")
                        }

                        override fun onServiceResolved(i: NsdServiceInfo) = emit(i)
                    })
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.w(TAG, "discovery failed to start: $code")
                close()
            }

            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }

        nsd.discoverServices(Net.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    private companion object {
        const val TAG = "Discovery"
    }
}
