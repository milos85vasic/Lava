package lava.api.app.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * [NsdManager]-backed [MdnsAdvertiser].
 *
 * Registers a single `NsdServiceInfo` for the build-appropriate service type
 * ([serviceTypeFor]) on the given port, with the TXT records [buildTxtRecords]
 * produces. The TXT map is staged via the pure builder so the wire contract is
 * verified by [MdnsAdvertiserTxtTest] without touching Android.
 *
 * @param engine the engine identity for this build variant (go / go-dev).
 * @param versionProvider supplies the embed's reported version name at
 *   register time (read from `ApiStatus.version` so the advertised version
 *   tracks the actual running embed, not a hardcoded literal — §6.R).
 */
class NsdMdnsAdvertiser(
    context: Context,
    private val engine: AdvertisedEngine,
    private val versionProvider: () -> String,
    private val serviceName: String = DEFAULT_SERVICE_NAME,
) : MdnsAdvertiser {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null

    override fun register(port: Int) {
        val nsd = nsdManager ?: return
        // A previous registration must be torn down before re-registering.
        unregister()

        val txt = buildTxtRecords(engine, versionProvider())
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = serviceTypeFor(engine)
            this.port = port
            for ((k, v) in txt) {
                setAttribute(k, v)
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override fun unregister() {
        val nsd = nsdManager ?: return
        registrationListener?.let { listener ->
            runCatching { nsd.unregisterService(listener) }
            registrationListener = null
        }
    }

    companion object {
        private const val DEFAULT_SERVICE_NAME = "Lava API"
    }
}
