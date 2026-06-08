# HelixQA own-org dependency submodules

> Created 2026-06-08 per operator directive: *"we MUST ADD all missing dependency
> Submodules which can be added from vasic-digital and HelixDevelopment orgs!
> Document this in project so we are aware of this fact!"*

## Why this exists

HelixQA (`submodules/helixqa`, Go module `digital.vasic.helixqa`) is the autonomous-QA
framework the operator wants running per-provider QA sessions against the Lava app.
Its `go.mod` **`replace` directives point at sibling paths** (`../X`) that resolve,
when HelixQA is mounted under `submodules/helixqa`, to **`submodules/X`** in the Lava
tree. HelixQA does **not** carry these own-org deps in its own `.gitmodules` — it expects
the **consuming project (Lava) to provide them as sibling submodules**. Until they are
present, `go build ./cmd/helixqa` fails ("reading ../doc_processor/go.mod: no such file
or directory"), and because `lava-api-go` does `replace digital.vasic.helixqa =>
../submodules/helixqa`, a broken/missing HelixQA dep tree can break the Lava Go build too.

## The 8 own-org (`digital.vasic.*`) sibling modules HelixQA requires

Source of truth: `submodules/helixqa/go.mod` `replace` block.

| Go module | sibling path (Lava) | vasic-digital repo | status |
|---|---|---|---|
| `digital.vasic.challenges` | `submodules/challenges` | `challenges` | ✅ already present |
| `digital.vasic.containers` | `submodules/containers` | `containers` | ✅ already present |
| `digital.vasic.security` | `submodules/security` | `security` | ✅ already present |
| `digital.vasic.docprocessor` | `submodules/doc_processor` | **DocProcessor** | ➕ added 2026-06-08 |
| `digital.vasic.llmorchestrator` | `submodules/llm_orchestrator` | **LLMOrchestrator** | ➕ added 2026-06-08 |
| `digital.vasic.llmprovider` | `submodules/llm_provider` | **LLMProvider** | ➕ added 2026-06-08 |
| `digital.vasic.llmsverifier` | `submodules/llms_verifier` (module in `/llm-verifier`) | **LLMsVerifier** | ➕ added 2026-06-08 |
| `digital.vasic.visionengine` | `submodules/vision_engine` | **VisionEngine** | ➕ added 2026-06-08 |

All 5 new ones were added from **`git@github.com:vasic-digital/<Repo>.git`** (the same
org + SSH scheme as the existing 3). Local paths are snake_case to match HelixQA's
`replace` targets exactly.

## NOT added (deliberately out of scope)

HelixQA's own `.gitmodules` lists **dozens of third-party `tools/opensource/*` submodules**
(scrcpy, appium, chroma, perfetto, ui-tars, moondream, mem0, leakcanary, docker-android,
midscene, marker, unstructured, signoz, redroid, …). These are **third-party** repos
(Genymobile, bytedance, google, square, etc.), **not** vasic-digital / HelixDevelopment,
so they are explicitly **excluded** from this addition per the operator's "from vasic-digital
and HelixDevelopment orgs" scope. They are runtime tools the full autonomous binary may
need; provisioning them is a separate, later task (`git -C submodules/helixqa submodule
update --init <path>` for the specific tools actually required at run time).

## Pin policy

Per the §6.AD Q9 waiver, the HelixQA ecosystem tracks upstream. The HelixQA pin itself was
bumped `5112906` → `dd3cf1d` on 2026-06-08 to escape a broken pinned commit whose `go.mod`
contained **unresolved git conflict markers** (L124/131/138) that broke the Go build. The
5 new sibling submodules were added at their current upstream HEAD; record their pins in
`docs/CONTINUATION.md` §3.

## Verification owed

- [ ] `cd submodules/helixqa && go build ./cmd/helixqa` succeeds (or surfaces the next
      missing own-org dep — repeat the add until only third-party `tools/opensource/*`
      remain).
- [ ] `cd lava-api-go && go build ./...` still green (confirmed green after the pin bump).
- [ ] §6.W: ensure each new submodule mirrors to GitHub **and** GitLab (own-org mirror mandate).
