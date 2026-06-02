// Package sqlitemigrations embeds the SQLite-dialect migration SQL for the
// lava-api-go SQLite storage backend so it ships inside the binary (no
// runtime file dependency). The SQL mirrors the Postgres response_cache
// schema; see 0001_init.sql for the column-by-column mapping.
package sqlitemigrations

import _ "embed"

// Init is the first (and currently only) SQLite migration: the response_cache
// table + expiry index. Applied idempotently (CREATE TABLE IF NOT EXISTS) by
// storage.NewSQLite on open.
//
//go:embed 0001_init.sql
var Init string
