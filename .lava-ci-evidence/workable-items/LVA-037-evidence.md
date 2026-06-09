# LVA-037 — §6.R hostport scanner exempts top-level tests/ hermetic fixtures
Commit 11ec89e1. Scanner regex had /test/ (src/test/) but missed top-level tests/ → flagged its own fixture.
Added ^tests/. check-constitution.sh now EXIT 0 (fully GREEN). Bluff-Audit: drop ^tests/ → re-flags fixture; reverted.
