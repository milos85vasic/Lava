package lava.api.app.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

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

        // Capture the configured instance name + service type into LOCALS before
        // the apply block. Inside `apply { }` the implicit receiver is the
        // NsdServiceInfo, whose own `serviceName` property SHADOWS this
        // advertiser's `serviceName` constructor val: a bare `serviceName` on the
        // RHS resolves to the receiver's (null) `serviceName`, so the service
        // would register with an EMPTY name that Android 15's NsdManager rejects
        // with IllegalArgumentException("The service name or the service type is
        // missing"), crashing engine start. The locals disambiguate the
        // reference. (Latent until C02 first actually ran via the :api-app gate.)
        val instanceName = serviceName
        val type = serviceTypeFor(engine)
        val txt = buildTxtRecords(engine, versionProvider())
        val info = NsdServiceInfo().apply {
            serviceName = instanceName
            serviceType = type
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
        // mDNS advertisement is best-effort LAN discovery; a platform-level
        // registration rejection MUST NOT crash the API engine, which serves its
        // core HTTPS surface independently. Degrade gracefully + log with full
        // context (§11.4.6 forensic capture). api-app has no AnalyticsTracker yet
        // — §6.AC non-fatal wiring is tracked as api-app debt.
        runCatching {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { e ->
            registrationListener = null
            Log.w(
                TAG,
                "mDNS registerService failed (advertisement degraded; API still " +
                    "serving): name='$instanceName' type='$type' port=$port",
                e,
            )
        }
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
        private const val TAG = "NsdMdnsAdvertiser"
    }
}
