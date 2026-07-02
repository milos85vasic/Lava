package digital.vasic.lava.client

/** Release no-op twin of the debug seam — guarantees NO test hook in release. */
object QaKeyInjection {
    val override: String? = null
}
