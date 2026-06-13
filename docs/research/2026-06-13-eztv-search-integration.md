# EZTV Free-Text Search Integration Research

**Date:** 2026-06-13
**Author:** research subagent (lava-api-go curated-providers stream)
**Scope:** Determine the *honest* way to make EZTV usable as a free-text-searchable curated provider in `lava-api-go`, or prove it cannot be done anonymously and recommend the alternative.
**Constitutional frame:** §6.E (Capability Honesty — a declared SEARCH capability MUST genuinely filter by the query) + §6.L / §6.J (no bluff: a search that ignores the query is forbidden). §11.4.6 no-guessing vocabulary applied throughout — captured facts are stated as facts; anything not directly observed is marked `UNCONFIRMED:`.

---

## 1. Probes run (literal commands + captured-output excerpts)

All probes are bounded single `curl`/`python3` invocations (no loops), run live on 2026-06-13.

### Probe 1 + 2 — `keywords=` param is silently ignored (re-confirm)

```
curl -s "https://eztvx.to/api/get-torrents?limit=5&page=1&keywords=ironman"
curl -s "https://eztvx.to/api/get-torrents?limit=5&page=1&keywords=batman"
```

Both responses begin **identically**:

```
keywords=ironman → {"torrents_count":1055208,"limit":5,"page":1,"torrents":[{"id":3110192,...,"title":"Iron Man and His Awesome Friends S01E28 720p WEB H264-AFO EZTV",...},{...Deadliest.Catch.S22E06...}]}
keywords=batman  → {"torrents_count":1055208,"limit":5,"page":1,"torrents":[{"id":3110192,...,"title":"Iron Man and His Awesome Friends S01E28 720p WEB H264-AFO EZTV",...},{...Deadliest.Catch.S22E06...}]}
```

**FACT:** `torrents_count` is `1055208` for both; the first torrent for `keywords=batman` is *"Iron Man and His Awesome Friends"* — the global latest-torrents list, unfiltered. The `keywords` param is a no-op. (Matches the main stream's earlier finding; count moved 1,055,207 → 1,055,208 only because the global feed grew by one row between sessions.)

### Probe 7 — `?q=` and `?search=` aliases are ALSO ignored

```
curl -s "https://eztvx.to/api/get-torrents?limit=1&q=batman"      | grep -o '"torrents_count":[0-9]*'   → "torrents_count":1055208
curl -s "https://eztvx.to/api/get-torrents?limit=1&search=batman" | grep -o '"torrents_count":[0-9]*'   → "torrents_count":1055208
```

**FACT:** Neither `q` nor `search` filters; both return the same global count. There is no free-text param on the JSON API under any of the three names tried.

### Probe 3 + 4 + 11 — `imdb_id=` param GENUINELY filters (but is TV-only and zero-pad-sensitive)

```
curl -s "https://eztvx.to/api/get-torrents?limit=3&imdb_id=0372784"   (Batman Begins, a film) → {"imdb_id":"0372784","torrents_count":0,...}
curl -s "https://eztvx.to/api/get-torrents?limit=3&imdb_id=0944947"   (Game of Thrones)       → {"imdb_id":"0944947","torrents_count":146,"torrents":[{...,"title":"Game of Thrones S01E10 2160p UHD BluRay x265-SCOTLUHD EZTV","imdb_id":"0944947",...}]}
```

Zero-padding is **load-bearing**:

```
curl -s ".../get-torrents?limit=1&imdb_id=944947"  → "torrents_count":0
curl -s ".../get-torrents?limit=1&imdb_id=0944947" → "torrents_count":146
```

**FACT:** `imdb_id` is a real filter. All returned rows for GoT are titled "Game of Thrones". A film (Batman Begins) returns 0 → EZTV is a TV-only catalogue. The id MUST be the zero-padded 7-digit form (`0944947`), not the integer-stripped form (`944947`). IMDb's own `ttXXXXXXX` id already carries this padding; the correct transform is `strip("tt")`, NOT `int()`.

### Probe 5 — HTML search page `/search/<query>` is HARD Cloudflare-gated

```
curl -s -A "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36" "https://eztvx.to/search/game-of-thrones" -D -
```

Response headers + body:

```
HTTP/2 403
cf-mitigated: challenge
server: cloudflare
content-security-policy: ... https://challenges.cloudflare.com ...
server-timing: chlray;desc="a0afb7828c2776ef"
--- body markers found: "Just a moment", "challenge-platform", "cloudflare"
--- count of "game of thrones" in body: 0
--- body size: 5536 bytes (the CF interstitial, not a results page)
```

**FACT:** The HTML search page returns **HTTP 403 with `cf-mitigated: challenge`** ("Just a moment…" JS challenge) to an anonymous client. The query term does not appear in the body — no results are served. This path requires solving a Cloudflare Turnstile/JS challenge (headless browser or a CF-bypass service), which a Go HTTP client cannot do anonymously.

### Probe 6 — the JSON API itself is NOT Cloudflare-gated

```
curl -s -o /dev/null -w "http_code=%{http_code}" ".../api/get-torrents?limit=1&imdb_id=0944947"  → http_code=200
```

**FACT:** `/api/get-torrents` returns 200 anonymously (no CF challenge). Only the HTML site surface is gated. So the JSON `imdb_id` path is the only anonymous, machine-usable surface.

### Probe 8 + 9 — anonymous, no-key title→IMDb resolver EXISTS

```
curl -s -A "Mozilla/5.0" "https://v3.sg.media-imdb.com/suggestion/x/game%20of%20thrones.json"
```

Parsed (`tt`-prefixed entries):

```
tt0944947  | Game of Thrones          | TV series | 2011
tt11198330 | House of the Dragon      | TV series | 2022
tt2653350  | Game of Thrones: Costumes| video     | 2011
```

**FACT:** IMDb's public suggestion endpoint `https://v3.sg.media-imdb.com/suggestion/x/<query>.json` returns title→`ttXXXXXXX` mappings **with no API key and no auth** (just a browser UA). It is the autocomplete backend of imdb.com. It distinguishes `q:"TV series"` from films/videos, which lets us pick the TV entry EZTV actually carries. (Contrast: OMDb and TMDb both require an API key → would violate the anonymous/no-credentials property of curated providers, so they are rejected.)

### Probe 10 + 12 — full chain works mechanically, but has a FALSE-MATCH defect

```
# Game of Thrones: tt0944947 → strip tt → 0944947 → EZTV 146 rows, all "Game of Thrones". CORRECT.

# Breaking Bad:
W=$(curl ... suggestion .../breaking%20bad.json | pick first TV-series tt)   → tt0903747
curl ".../get-torrents?limit=2&imdb_id=0903747"
  → "torrents_count":77
  → "title":"Breaking Brad S02E02 ... EZTV"
  → "title":"Breaking Brad S09E02 ... EZTV"
```

**FACT — IMPORTANT DEFECT:** The IMDb-suggest top "TV series" hit for "breaking bad" resolved to `tt0903747`, and EZTV returned **77 rows titled "Breaking *Brad*" — a different show**, not "Breaking Bad". Two non-exclusive explanations, neither yet isolated:
- `UNCONFIRMED:` the IMDb suggest API returned a wrong/ambiguous id for the bare query "breaking bad" (the canonical Breaking Bad id is `tt0903747` per common knowledge, so the id may be right and EZTV's per-row `imdb_id` tagging may be wrong);
- `UNCONFIRMED:` EZTV's per-torrent `imdb_id` field is operator-supplied and sometimes mis-tagged, so an `imdb_id` query can surface mislabeled rows.

The honesty consequence is the same regardless of cause: **the title→imdb→get-torrents chain can return results that do not match the user's typed title.** This is a §6.E/§6.L risk surface that any shipped implementation MUST mitigate (e.g. post-filter returned rows by fuzzy title match against the query, and/or pin to a curated id map rather than live-resolving).

### Probe 13 — replacement candidate `torrents-csv` has genuine free-text search (re-confirm)

```
curl -s "https://torrents-csv.com/service/search?q=batman&size=2"
  → {"torrents":[{"infohash":"0c23e50e...","name":"The Batman (2022) [1080p] [WEBRip] [5.1] [YTS.MX]","seeders":357,...},{...}]}
```

**FACT:** `torrents-csv.com/service/search?q=` genuinely filters by free text (batman → "The Batman (2022)", with real seeder counts), anonymously, JSON, no key. This is the already-shipped 2nd curated provider in lava-api-go.

---

## 2. Findings table

| Endpoint / approach | Anonymous? | Cloudflare-gated? | Genuinely filters by free-text title? | Evidence |
|---|---|---|---|---|
| `GET /api/get-torrents?keywords=<q>` | Yes | No (200) | **NO** — param ignored | Probe 1/2: `ironman` and `batman` both return count 1055208 + identical first row |
| `GET /api/get-torrents?q=<q>` | Yes | No | **NO** — ignored | Probe 7: count 1055208 |
| `GET /api/get-torrents?search=<q>` | Yes | No | **NO** — ignored | Probe 7: count 1055208 |
| `GET /api/get-torrents?imdb_id=<padded7>` | Yes | No (200) | **Partial** — filters by IMDb id, NOT free text; TV-only; needs zero-pad; can mis-match | Probe 3/4/11/12 |
| `GET /search/<query>` (HTML) | No (needs CF solve) | **YES — 403 `cf-mitigated: challenge`** | n/a (blocked) | Probe 5: 403, "Just a moment", 0 hits in body |
| IMDb suggest `v3.sg.media-imdb.com/suggestion/x/<q>.json` | Yes (no key) | No | title→`tt` id (resolver only, not a tracker) | Probe 8/9 |
| **Chain:** IMDb-suggest → strip `tt` → `imdb_id=` | Yes | No | **Approximate** — works for GoT; FALSE-MATCHED "breaking bad"→"Breaking Brad" | Probe 10/12 |
| Jackett's own EZTV definition (`eztv.yml`) | — | — | Scrapes the **HTML `search/<kw>` page** (the CF-gated one) | §3 below |
| `torrents-csv.com/service/search?q=` (replacement) | Yes (no key) | No | **YES** | Probe 13 |

---

## 3. How mature integrations (Jackett) do EZTV title search

Jackett's current EZTV indexer is a **Cardigann YAML definition**, not the JSON API. Fetched live from
`https://raw.githubusercontent.com/Jackett/Jackett/master/src/Jackett.Common/Definitions/eztv.yml`:

```yaml
caps:
  modes:
    search: [q]
    tv-search: [q, season, ep]
search:
  paths:
    - path: "{{ if .Keywords }}search/{{ .Keywords }}{{ else }}home{{ end }}"
  keywordsfilters:
    - name: re_replace
      args: ["\\bS\\d{2,3}\\b", ""]   # strip season tag — search doesn't support it
    - name: trim
    - name: replace
      args: ["-", ""]
    - name: replace
      args: [" ", "-"]                # spaces → hyphens
    - name: replace
      args: ["&", ""]
  headers:
    cookie: ["sort_no=100; q_filter=all; ... layout=def_wlinks"]
  rows:
    selector: "table.forum_header_border:contains('Latest') tr[name='hover']... :has(a.magnet), ...:contains('Releases')..."
    filters:
      - name: andmatch        # client-side AND-match of keyword terms against parsed rows
```

**FACT:** Jackett does **NOT** use the JSON API for EZTV search and does **NOT** use `imdb_id`. It requests the **HTML page** `https://<eztv-mirror>/search/<hyphenated-keywords>`, parses the results `<table>` rows, and applies a client-side `andmatch` keyword filter on the parsed titles. EZTV's own search "doesn't support" season tags (Jackett strips them), confirming EZTV HTML search is a crude substring-on-title match.

**Consequence for lava-api-go:** Jackett's path is exactly the surface Probe 5 proved is **Cloudflare-challenge-gated (403)** for an anonymous HTTP client. Jackett works because it (a) runs with a configurable FlareSolverr/Cloudflare-bypass proxy and/or per-user mirror selection, and (b) is a user-hosted app, not an anonymous server-side scraper. lava-api-go curated providers are anonymous, no-bypass, server-side Go HTTP — they cannot reproduce Jackett's path honestly. `UNCONFIRMED:` whether specific listed mirrors (`eztv.wf`, `eztv.tf`, `eztv.yt`, `eztv1.xyz`) are un-gated; the primary `eztvx.to` is gated. Mirror-roulette is not a stable basis for a shipped capability.

---

## 4. Title→IMDb resolver evaluation (task 3)

- **IMDb suggest API** (`v3.sg.media-imdb.com/suggestion/x/<q>.json`): anonymous, no key, returns title→`tt` id + media-type. **Viable as a resolver.** Risks: (a) it is an undocumented endpoint that could change/rate-limit without notice (`UNCONFIRMED:` long-term stability); (b) ambiguous queries resolve to the wrong id (Probe 12 "breaking bad"→"Breaking Brad" false match); (c) adds an extra network hop + external dependency into every EZTV search.
- **OMDb / TMDb:** both require an API key → **REJECTED** — violates the anonymous/no-credentials property that defines a curated provider in this project (§6.H credential-inviolability + the curated-provider design contract).
- **Net:** a key-free resolver exists, but the chain's free-text fidelity is only *approximate* and demonstrably wrong for at least one common query. Shipping it as a free-text `CapSearch` would be borderline-bluff under §6.E unless paired with a strict post-filter (returned-row title must fuzzy-match the user query) — and even then it cannot find shows EZTV failed to imdb-tag.

---

## 5. RECOMMENDATION

**Primary: `[skip EZTV]` as a free-text-search curated provider.**

Rationale (all captured, not surmised):
1. EZTV's JSON API has **no working free-text param** — `keywords`, `q`, `search` are all silently ignored (Probe 1/2/7). Declaring a free-text SEARCH capability against it is the exact §6.E/§6.L "search ignores the query" bluff that is STRICTLY FORBIDDEN here.
2. The only genuine HTML title-search surface is **hard Cloudflare-challenge-gated (HTTP 403, `cf-mitigated: challenge`)** for anonymous clients (Probe 5) — the same surface Jackett uses, which is why Jackett needs a CF-bypass and a user host. lava-api-go anonymous server-side Go cannot replicate it honestly.
3. The `imdb_id` workaround is **not free-text search**: it is TV-only, zero-pad-sensitive, requires an external IMDb-suggest hop, and **produced a false match** ("breaking bad" → "Breaking Brad", Probe 12). It cannot honestly back a free-text `CapSearch`.

**Strongest single piece of evidence for the recommendation:** Probe 1/2 — `keywords=ironman` and `keywords=batman` return the **byte-identical** global list (`torrents_count:1055208`, first row "Iron Man and His Awesome Friends" for *both* queries). The query has zero effect on the JSON API output. Any free-text SEARCH capability declared against this endpoint is provably non-functional, i.e. a constitutional bluff.

### Acceptable honest alternatives, in priority order

- **(A) `[replace EZTV with another free-text JSON tracker]` — RECOMMENDED concrete action.** The already-probed, already-shipped `torrents-csv.com/service/search?q=` (Probe 13) and apibay.org (The Pirate Bay) both have genuine anonymous key-free free-text JSON search and **already cover TV content** (Probe 13's batman result set includes TV-style releases). EZTV adds no honest free-text capability the existing two providers lack. Net: do not add EZTV; the curated set's free-text search is already satisfied by TPB + torrents-csv.
- **(B) `[ship EZTV honestly with imdb-id-only, no free-text CapSearch]` — only if a *browse/by-show* capability is explicitly wanted.** EZTV could be exposed as an **IMDb-id / by-show browse** provider that does NOT declare free-text `CapSearch`. The `imdb_id` filter is genuine (Probe 4: 146 real GoT rows). This is honest *only* if the capability surfaced to the user is "browse a show by its IMDb id", not "free-text search". It would still require: zero-padding the id, and (if title-driven) a strict post-filter on returned-row titles to defend against the Probe-12 false-match class. This is more wiring for a narrower, non-free-text capability — recommend only on explicit product demand.
- **(C) `[ship EZTV honestly with HTML-search parse]` — REJECTED.** Blocked by Cloudflare (Probe 5). Would require a CF-bypass dependency, violating the anonymous/no-credentials curated-provider contract. Do not pursue.

**Bottom line:** Nothing on EZTV gives anonymous, server-side, genuine free-text title search. Recommend **skipping EZTV** and relying on the two providers that already have honest free-text JSON search (The Pirate Bay + Torrents-CSV). If a by-show capability is later wanted, option (B) (imdb-id browse, no free-text CapSearch, with title post-filter) is the only honest EZTV path.
