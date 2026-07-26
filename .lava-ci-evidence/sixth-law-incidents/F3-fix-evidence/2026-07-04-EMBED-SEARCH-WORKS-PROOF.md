# F3+F4 RESOLVED — api-app embed search PROVEN LIVE 2026-07-04

On-device api-app embed (x86_64 emulator, post-F3-fix) serving REAL search over guest WAN.
Auth: Lava-Auth header = base64(16 random bytes) per-install key (ApiKeyStore). Endpoint /v1/{provider}/search.

| provider x query | HTTP | bytes | results |
|---|---|---|---|
| archiveorg x story | 200 | 9267 | ~50 |
| archiveorg x linux | 200 | 9080 | ~50 |
| torrentscsv x 1080p | 200 | 17610 | ~25 |
| gutenberg x linux | 200 | 196 | ~1 (genuine catalogue match) |

Sample archiveorg×story titles (real WAN results):
  - "title":"The Internet Archive Software Collection
  - "title":"CD-ROM Software Collection
  - "title":"Computer Magazines
  - "title":"SermonIndex.net: Promoting Authentic Biblical Christian

Note: the earlier 401s were a TEST error (Auth-Token header — the provider-cookie header — used instead of the Lava-Auth client-credential header). With the correct Lava-Auth header the embed authenticates + serves results. This is the SAME auth the real client (ApiBackedTrackerClient) uses.
