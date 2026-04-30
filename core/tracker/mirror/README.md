# :core:tracker:mirror

Lava-domain bridge to the generic `lava.sdk:mirror` primitive in the
`Submodules/Tracker-SDK/` composite-build pin. Hosts the
Lava-domain `MirrorConfigStore` typealias and any Lava-specific
serialization glue.

## Public API

```kotlin
// Re-exported from lava.sdk:mirror via typealias for ergonomics —
// feature ViewModels see a Lava-domain type even though the
// implementation lives in the vasic-digital submodule.
typealias MirrorConfigStore = lava.sdk.mirror.MirrorConfigStore
```

## Why a separate module?

Three reasons:

1. **Decoupled Reusable Architecture rule.** The generic mirror-config
   store interface and the in-memory implementation belong in
   `vasic-digital/Tracker-SDK` — they are not Lava-specific. This
   module is the thin Lava-side glue that imports the primitive and
   re-exports it under a Lava-domain type name.

2. **Future-proofing for serialization.** If/when the `mirrors.json`
   schema gains Lava-specific extensions (e.g. region-aware health
   probes, captcha cookie stores), they live here, not upstream.

3. **Test ergonomics.** Tests in this module use the
   `lava.sdk:testing` fixture-loader against real `mirrors.json`
   files. Keeping the seam Lava-side means we control the fixture set
   without round-tripping through the submodule.

## Dependencies

```
:core:tracker:mirror
   ├── :core:tracker:api
   ├── lava.sdk:api          (MirrorUrl, Protocol)
   ├── lava.sdk:mirror       (MirrorConfigStore interface)
   └── kotlinx-serialization-json
```

## Test discipline

Tests in this module exercise the actual `mirrors.json` parsing logic
against real shipped fixtures. Per Sixth + Seventh Laws:

- No mocking of `MirrorConfigStore`, `MirrorConfigLoader`, or any
  class under `lava.tracker.mirror.*`.
- Primary assertions on parsed-state correctness (the mirror set
  loaded from a fixture matches the fixture content), not on
  `verify { … }` calls.
- Bluff-Audit stamp required on every test commit (Seventh Law
  clause 1, pre-push hook enforced).

> See also: Sixth Law and Seventh Law in root `CLAUDE.md`. The
> Decoupled Reusable Architecture rule constrains every change here:
> generic functionality goes UPSTREAM to `vasic-digital/Tracker-SDK`
> first, Lava pin updates second.
