# LVA-028 closure evidence — nnmclub search publishDate
Commit: a31efce8 fix(core/tracker/nnmclub): LVA-028 — parse search publishDate (was dropped)
Parser read cells[5] (Size) but never cells[4] (ISO yyyy-MM-dd Date) → publishDate always null.
Added NnmclubDateParser + wired publishDate from the Date column.
Main-stream re-verify: NnmclubSearchParserDateTest tests=1 failures=0; :core:tracker:nnmclub:test BUILD SUCCESSFUL.
Bluff-Audit: forced publishDate=null → expected:<2024-01-15T00:00:00Z> but was:<null>; reverted.
