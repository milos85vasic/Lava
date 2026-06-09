# LVA-025 / LVA-026 closure evidence — v1 captcha fixes
Commit: aca3e720 fix(lava-api-go): LVA-025/026 — v1 captcha dynamic field name + propagate upstream Content-Type
- LVA-025: LoginOpts.CaptchaName carries dynamic cap_code_<sid>; adapter submits <field>=<answer>.
- LVA-026: CaptchaImage.ContentType propagated; v1 handler serves real type w/ image/jpeg fallback.
Main-stream re-verify: go build OK, go vet OK, internal/handlers/v1 ok, internal/rutracker ok, tests/contract SourceHash ok.
Bluff-Audit (both): reproduce-fail captured, post-fix pass, mutation re-fail captured, reverted. See commit body.
