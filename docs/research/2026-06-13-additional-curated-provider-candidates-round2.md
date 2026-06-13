# Additional Curated Provider Candidates — Round 2 Live-Probe Research (2026-06-13)

Round-2 research feeding **Defect B** (on-device api-app embeds curated Go
providers in `lava-api-go/internal/provider/curated/`).

**Already shipped (6) — NOT re-proposed here:** The Pirate Bay (`apibay.org`),
YTS (`yts.mx`), Torrents-CSV (`torrents-csv.com`), BitSearch (`bitsearch.eu`),
Knaben (`api.knaben.org`), Nyaa (`nyaa.si` RSS).

**Already rejected with evidence (round 1 + prior) — NOT re-probed:** EZTV
(keyword no-op), 1337x (CF challenge), TorrentGalaxy (DNS dead), SolidTorrents
(= BitSearch backend), Torlock (HTML timeout), torrent-api-py (404).

All probes were live, server-side `curl` GETs from a macOS host on 2026-06-13
(~09:38–11:50 UTC), bounded ≤18s each, default UA
`Mozilla/5.0 (compatible; LavaResearchBot/1.0)` unless noted. Per §11.4.6
no-guessing vocabulary: only captured facts are stated; anything not directly
probed is marked `UNCONFIRMED:`.

## The bar (ALL must hold, each PROVEN with a captured probe)

1. Free-text query genuinely FILTERS — two distinct queries return DIFFERENT
   result sets that each contain the query term (the EZTV no-op test).
2. Anonymous — no API key, no login, no cookie.
3. NOT Cloudflare-challenge-gated for a plain server-side Go GET (no HTTP 403 +
   `cf-mitigated: challenge` / "Just a moment").
4. Returns an `info_hash` or magnet.
5. Stable JSON (preferred) or RSS / trivially parseable response.

---

## Candidate results

### 1. TorrentDownloads (`torrentdownloads.pro`) — VIABLE ✅ (TOP PICK)

- **Search endpoint:**
  `GET https://www.torrentdownloads.pro/rss.xml?type=search&search=<query>`
  (a `www.torrentdownloads.pro` host; the bare host issues a redirect — follow
  it / pin `www.`).
- **Live probe:** `HTTP 200`, body is RSS XML (`<?xml ... <rss version='2.0'>`),
  `bytes≈26627` for q=ubuntu. `server: cloudflare` present but **`cf-mitigated`
  ABSENT** — passive CDN, NOT a challenge gate. Works with a plain non-browser
  UA (`LavaServerBot/1.0` → 50 items), so no UA-gating.
- **Auth:** anonymous; no key/cookie.
- **info_hash / magnet:** YES — dedicated `<info_hash>` element, 40-hex
  lowercase (e.g. `19987310611f0fae3b2c9678601b92967d938aff`). Magnet is built
  client-side from the hash + the curated public-tracker list (same pattern as
  the Nyaa provider).

**2-query filter-difference proof (the EZTV no-op test) — PASSES decisively:**

```
Q1 search=ubuntu → 50 items; 50/50 <title> contain "ubuntu"
   ("Ubuntu 12 10 Server 64 Bit", "Ubuntu 10 10 i386 v14 2 5", ...)
Q2 search=debian → 50 items; 50/50 <title> contain "debian"
overlap between the two title sets: 0
```

**Field mapping (RSS `<item>` → provider.SearchItem):**

| RSS element        | provider.SearchItem | Notes |
|--------------------|---------------------|-------|
| `<title>`          | `Title`             | plain text |
| `<info_hash>`      | `InfoHash`          | 40-hex lowercase; build magnet from it |
| `<size>`           | `SizeBytes`         | integer bytes (e.g. `24898149`) |
| `<seeders>`        | `Seeders`           | integer (e.g. `978`) |
| `<leechers>`       | `Leechers`          | integer (e.g. `30`) |
| `<pubDate>`        | `Date`              | RFC-1123 (`Mon, 21 Oct 2013 13:20:49 +0200`) |
| `<link>`           | (detail URL)        | site-relative `/torrent/<id>/<slug>` |

**VERDICT: VIABLE.** Best round-2 candidate — full field set (the ONLY round-2
candidate with native `<info_hash>` + `<seeders>` + `<leechers>` + `<size>` all
in the feed), clean filter, passive CF, no UA-gating. General-purpose corpus
(complements the niche providers). Maps cleanly onto the `nyaa` RSS provider
shape. **Caveat (UNCONFIRMED:):** `.pro` is one of several rotating
TorrentDownloads mirror TLDs; long-term host stability of `.pro` specifically is
not proven beyond today's probe.

---

### 2. Tokyo Toshokan (`tokyotosho.info`) — VIABLE ✅ (anime/niche, no seeders)

- **Search endpoint:**
  `GET https://www.tokyotosho.info/rss.php?terms=<query>`
- **Live probe:** `HTTP 200`,
  `content-type: application/rss+xml; charset=utf-8`. `server: cloudflare`,
  `cf-mitigated` ABSENT — passive CDN.
- **Auth:** anonymous.
- **info_hash / magnet:** YES — each item's `<description>` (CDATA) embeds a
  full `magnet:?xt=urn:btih:<BASE32-40>&tr=...` link (e.g.
  `magnet:?xt=urn:btih:AIHBWH5HWF2UQCHMDT2D3O6TP63HBLNX&tr=...`). Note the
  btih is **base32** (32 chars), not 40-hex — the provider must accept the
  magnet as-is OR base32-decode → hex.

**2-query filter-difference proof — PASSES:**

```
Q1 terms=naruto → 139 items; first content titles on-topic
   ("Cutie Kuromi - Naruto chan.zip", "...(NARUTO -ナルト-).zip", ...)
Q2 terms=bleach → 146 items; on-topic
   ("Bleach The new Gotei-13 law - Final [ENG].zip", "...(Bleach)...", ...)
disjoint, query-specific result sets.
```

**Field mapping (RSS `<item>` → provider.SearchItem):**

| RSS element                         | provider.SearchItem | Notes |
|-------------------------------------|---------------------|-------|
| `<title>`                           | `Title`             | plain text |
| `magnet:` URL inside `<description>`| `InfoHash` / magnet | base32 btih; regex-extract from CDATA |
| `Size: NNN MB` inside `<description>`| `SizeBytes`        | human-readable; must parse "113.89MB" |
| `<pubDate>`                         | `Date`              | RFC-1123 GMT |
| `<category>`                        | (category)          | e.g. "Other", "Hentai (Manga)" |
| seeders / leechers                  | — (ABSENT)          | **NOT in the feed** → emit 0/unknown |

**VERDICT: VIABLE.** Honest CapSearch (genuine filter), anonymous, magnet
present. **Two caveats:** (a) NO seeders/leechers in the feed (must surface as
0/unknown — UI honesty); (b) btih is base32, and size needs human-string
parsing. Niche (anime/Japanese media) — overlaps Nyaa's domain but draws a
distinct corpus + different uploaders. Lower priority than TorrentDownloads
because of the missing seeders + base32 + the adult-heavy default result mix.

---

### 3. LimeTorrents (`limetorrents.lol` → `.fun`) — VIABLE ⚠️ (redirect + indirect hash)

- **Search endpoint:**
  `GET https://www.limetorrents.lol/searchrss/<query>/`
  — issues `HTTP 301 → https://www.limetorrents.fun/searchrss/<query>/`
  (final host serves the RSS). **Must follow the redirect** (the `.lol`/`.fun`
  TLD rotates; pinning a single TLD is fragile).
- **Live probe (final host):** `HTTP 200`, RSS XML, `server: cloudflare`,
  `cf-mitigated` ABSENT — passive CDN.
- **Auth:** anonymous.
- **info_hash / magnet:** INDIRECT — no `<info_hash>` element and no magnet; the
  40-hex hash is embedded in the `<enclosure url>` pointing to
  `https://itorrents.net/torrent/<40HEX>.torrent` (e.g.
  `itorrents.net/torrent/232CD67EB3FFBD7C37BF9EC3EE887417E5AE1EE6.torrent`).
  Must regex-extract the `[A-F0-9]{40}` and build the magnet client-side.

**2-query filter-difference proof — PASSES:**

```
Q1 searchrss/ubuntu/ → 40 items; 41 <title> lines incl. ubuntu (41 = 40 items + feed header)
Q2 searchrss/debian/ → 40 items; 41 <title> lines incl. debian
overlap between the two title sets: 0
```

**Field mapping (RSS `<item>` → provider.SearchItem):**

| RSS element                     | provider.SearchItem | Notes |
|---------------------------------|---------------------|-------|
| `<title>`                       | `Title`             | plain text |
| `<enclosure url>` (itorrents)   | `InfoHash`          | extract `[A-F0-9]{40}` from URL; build magnet |
| `<size>`                        | `SizeBytes`         | integer bytes |
| `Seeds: N , Leechers M` in `<description>` | `Seeders`/`Leechers` | regex-parse the description string |
| `<pubDate>`                     | `Date`              | e.g. `12 Dec 2025 10:15:20 +0200` (non-standard, no weekday) |

**VERDICT: VIABLE (lowest of the three).** Genuine filter, passive CF, hash +
seeders recoverable — but two friction points lower its rank: (a) hard
dependency on a `.lol → .fun` redirect on a rotating-TLD host (the most fragile
of the three); (b) hash is indirect (URL-embedded, uppercase hex) and
seeders/leechers need free-text regex out of `<description>`. Acceptable as a
later addition, behind TorrentDownloads + TokyoTosho.

---

## REJECTED candidates (each with captured evidence)

### GloDLS (`glodls.to` → `gtorrents.site`) — REJECTED (EZTV-class no-op)

- `GET https://glodls.to/rss.php?q=<query>` → `302 → gtorrents.site/rss.php`,
  final `HTTP 200`, `application/xhtml+xml`.
- **Filter is a NO-OP** (the canonical EZTV trap): `q=ubuntu` and `q=debian`
  returned the **byte-identical 50-item "latest torrents" feed** — `<guid>` lists
  diff'd IDENTICAL; 0/50 titles contained the query term in either feed (first
  item for both was "Iron Maiden - Virtual XI ... [FLAC]"). The `q` parameter is
  ignored. **Fails bar #1.**

### Torrent-Paradise (`.ml` / `.org` / `.eu`) — REJECTED (dead / parked)

- `torrent-paradise.ml/api/search?q=ubuntu` → `HTTP 200` but
  `content-type: text/html` serving a **Hungarian online-casino spam page**
  ("Magyar online kaszinók 2026 ...") — the domain has been repurposed; the JSON
  API is gone. `torrent-paradise.org` and `torrent-paradise.eu` → `curl (6)
  Could not resolve host`. **Fails bar #1/#5 (no API).**

### AcademicTorrents (`academictorrents.com`) — REJECTED (no per-query search feed)

- The site explicitly **blocks browse-scraping** (`browse.php` → `307` →
  `checkb.htm`, an HTML page that says: *"Hello if you are reading this you may
  be trying to scrape the browse page ... we instead ask you to search an XML
  file (in RSS format)"*). The offered feeds — `rss.xml` (recent) and
  `database.xml` (full DB dump) — take **NO query parameter**: `rss.xml` returns
  a fixed 44 KB "Recent Torrents" list regardless of input. `apiv2/.../search`
  → `404`. There is no anonymous free-text-filtering endpoint. **Fails bar #1.**

### Anidex (`anidex.info`) — REJECTED (unstable / down)

- `GET https://anidex.info/rss/?q=naruto` → first attempt `curl (28)` timeout
  (15 s, 0 bytes); retry with a browser UA → `HTTP 502` (Cloudflare error page
  "Error 502"). The origin is unreachable/unstable. **Fails bar #3/#5.**

### ext.to (`ext.to`, ExtraTorrent mirror) — REJECTED (403)

- `GET https://ext.to/rss/?q=ubuntu` → `HTTP 403` (`text/html`). Server-side GET
  is refused. **Fails bar #3.**

### iDope (`idope.se`) — REJECTED (dead)

- `GET https://idope.se/torrent-list/ubuntu/?r=1` → `curl (28)` connection
  timeout (12 s). Host unreachable. **Fails bar #5.**

### Snowfl (`snowfl.com`) — REJECTED (token-gated, not anonymous-stable)

- `GET https://snowfl.com/index1.php?ubuntu&<token>&...` → `HTTP 404` "Error".
  Snowfl's search requires a per-session token minted by its JS bundle; there is
  no stable anonymous query endpoint a plain Go GET can hit. **Fails bar #2/#5.**

### Bitmagnet public instances — REJECTED (no stable public host)

- `bitmagnet.io/graphql` → `404`; `demo.bitmagnet.io` → DNS does not resolve.
  Bitmagnet is self-host software, not a public aggregator with a stable
  anonymous endpoint to pin. **Fails bar #5.**

### BTDigg (`btdig.com`) — REJECTED (unreachable)

- `GET https://btdig.com/search?q=ubuntu&api=1` → `curl (28)` timeout (12 s).
  **Fails bar #5.**

---

## Ranked VIABLE shortlist (ready to implement)

| Rank | Provider | Endpoint | info_hash | Seeders/Leechers | Filter proof | Friction |
|------|----------|----------|-----------|------------------|--------------|----------|
| **1** | **TorrentDownloads** | `GET …torrentdownloads.pro/rss.xml?type=search&search=<q>` | native `<info_hash>` 40-hex | YES (native `<seeders>`/`<leechers>`) | 50/50 on-topic, 0 overlap | follow bare→`www` redirect; rotating-TLD (UNCONFIRMED: longevity) |
| **2** | **Tokyo Toshokan** | `GET …tokyotosho.info/rss.php?terms=<q>` | magnet (base32 btih) in `<description>` | NO (absent) | 139/146 disjoint, on-topic | base32 hash; size as human string; no seeders; adult-heavy mix |
| **3** | **LimeTorrents** | `GET …limetorrents.lol/searchrss/<q>/` (→ `.fun`) | indirect (40-hex in itorrents enclosure URL) | YES (regex from `<description>`) | 40/40 on-topic, 0 overlap | `.lol→.fun` redirect on rotating-TLD host; indirect hash |

All three: anonymous, passive-Cloudflare (no `cf-mitigated` challenge), RSS,
implementable against the `internal/provider/curated/nyaa` RSS pattern.

## Single best next-to-implement

**TorrentDownloads (`torrentdownloads.pro`).** Strongest evidence line:

> `search=ubuntu` and `search=debian` each return 50 RSS items, 50/50 titles
> on-topic, **0 overlap**, with a native `<info_hash>` 40-hex element plus
> `<seeders>`/`<leechers>`/`<size>`/`<pubDate>` — and `server: cloudflare` with
> `cf-mitigated` ABSENT, served identically to a plain `LavaServerBot/1.0` UA
> (no browser/UA gating).

It is the only round-2 candidate that ships a complete native field set
(hash + seeders + leechers + size + date) in the feed, making it the cleanest
drop-in clone of the existing Nyaa provider.
