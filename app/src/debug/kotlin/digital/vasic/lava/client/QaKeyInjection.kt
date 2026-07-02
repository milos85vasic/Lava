package digital.vasic.lava.client

/**
 * Debug-only test seam (§6.AK autonomous-QA). Challenge70 sets [override] to the
 * standalone Go API's derived Lava-Auth key (qa_key) so the REAL
 * OnboardingViewModel.withLocalApiKeyIfMissing() path keys the cloud-input
 * Endpoint.GoApi — the same production keying path the on-device api-app uses.
 * §6.H: the value is never logged. Null by default → zero behavior change.
 */
object QaKeyInjection {
    @Volatile var override: String? = null
}
