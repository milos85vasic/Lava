# LVA-029 closure evidence — isLocalHost fc/fd ULA false-positive
Commit: 4e687769 fix(core/network): LVA-029 — isLocalHost fc/fd ULA false-positive on public hosts
take(4).toIntOrNull(16) on "fcba" (fcbarcelona.com) = 0xfcba in fc00..fdff range → wrongly local.
Fix requires a ':' (genuine IPv6 literal) before applying the fc00::/7 first-hextet test.
Main-stream re-verify: HostUtilsTest tests=15 failures=0; :core:models:test BUILD SUCCESSFUL.
Bluff-Audit: reverted to loose startsWith → public-host test FAILED; reverted back.
