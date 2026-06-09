# LVA-032 — rutracker browse/favorites blind-cast Topic→Torrent type confusion
Commit fab05f2e. fromCategoryPage/fromFavoritesDto used AsForumTopicDtoTorrent (ignores discriminator)
→ Topic variants rendered as empty fake-torrent rows. Fixed via AsForumTopicDtoTorrentChecked.
Re-verified main tree: go build OK, internal/rutracker ok, tests/contract SourceHash ok. Bluff-Audit in commit.
