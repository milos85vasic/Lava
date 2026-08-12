# LVA-087 — device-verified §6.AK reproduce-first evidence

"Video #6 — Welcome claims '4 providers available' but picker lists ~12"

## Source verification

- `feature/onboarding/src/main/kotlin/lava/onboarding/OnboardingViewModel.kt:472`
  -- `welcomeProviderCount = if (apiSelectionEnabled) null else items.size`,
  `items` derived from `sdk.listAvailableTrackers()` filtered to
  verified/apiSupported/non-clone descriptors (the real registry, not
  a hardcoded literal).
- `feature/onboarding/src/main/kotlin/lava/onboarding/OnboardingHiltModule.kt:26`
  -- `apiSelectionEnabled(): Boolean = true` (always true in production).
- Net effect: the shipped Welcome screen never renders a numeric
  count -- it always shows the count-free "Multiple content providers
  available" copy, since in the API-selection flow the provider list
  is repopulated from the chosen API's catalogue on a later screen.

## Device verification (containerized Android 14 / API 34 emulator,
podman container lava-emu-CZ_API34_Phone-..., ADB serial
localhost:42205)

Test: `Challenge63WelcomeCountMatchesPickerTest.welcomeOmitsStaleProviderCount_inApiSelectionFlow`

- GREEN (unmodified production code): `BUILD SUCCESSFUL in 5m 16s`;
  test passed (5.092s).
- RED (falsifiability rehearsal -- `welcomeProviderCount` hardcoded to
  `4`, reproducing the exact reported bug): `BUILD FAILED in 1m 3s`;
  test FAILED with `java.lang.AssertionError: Welcome MUST show the
  count-free copy 'Multiple content providers available' in the
  API-selection flow...`.
- GREEN after revert (mutation reverted, `git diff` verified empty):
  `BUILD SUCCESSFUL in 1m 1s`; test passed again (3.947s).

## Outcome

Verified already-correct -- no production code change required.
Verified by a dispatched subagent per LVA-087 delegation; re-applied
to the canonical DB in the main worktree since the subagent's own
commit (`2d7c2d38` in a recovery worktree) could not be merged
directly (binary SQLite DB divergence against concurrent LVA-3/LVA-090
work in the main worktree).
