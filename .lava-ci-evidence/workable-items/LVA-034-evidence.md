# LVA-034 — Room endpoint-list converter drops GoApi platform/storage/key
Commit 488185ed. host:port-only packing dropped key → list-selected on-device endpoint 401s (Lava-Auth dead).
Fixed via #-sentinel percent-encoded packing (no migration). Re-verified: EndpointEntityGoApiFieldsTest 6/0/0. Bluff-Audit in commit.
