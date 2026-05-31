// Command lava-tickets is the LVA workable-items ticket system CLI.
//
// It is the Lava materialization of HelixConstitution §11.4.93 (SQLite-backed
// single-source-of-truth for workable items) + §11.4.95 (DB TRACKED in git) +
// §11.4.106 (mechanical doc<->DB sync engine, byte-identical round-trip) and the
// item-tracking covenant §11.4.15/16/33/34. The project ticket key prefix is
// LVA (operator directive, §6.L 68th invocation, 2026-05-31; design at
// docs/tickets/DESIGN.md).
//
// The SQLite DB (docs/tickets/tickets.db) is the single source of truth. The
// four markdown trackers (Issues.md / Fixed.md / Issues_Summary.md /
// Fixed_Summary.md) are derived artifacts produced by `gen`; `verify` regenerates
// to a temp dir and asserts byte-identity with what is on disk (§11.4.106). HTML
// export is pure-Go (hand-rolled, no external tool). PDF/DOCX export is attempted
// via a container runtime running pandoc; if neither is available the command
// reports an honest operator-actionable message and exits non-zero (NEVER fakes
// the file) — §6.J anti-bluff.
//
// Pure Go, no CGO: uses modernc.org/sqlite. No sudo, no network at runtime.
package main

import (
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	_ "modernc.org/sqlite"
)

// version is reported by `lava-tickets version`.
const version = "1.0.0"

func main() {
	if len(os.Args) < 2 {
		usage(os.Stderr)
		os.Exit(2)
	}
	cmd := os.Args[1]
	args := os.Args[2:]

	var err error
	switch cmd {
	case "init":
		err = cmdInit(args)
	case "add":
		err = cmdAdd(args)
	case "update":
		err = cmdUpdate(args)
	case "close":
		err = cmdClose(args)
	case "reopen":
		err = cmdReopen(args)
	case "gen":
		err = cmdGen(args)
	case "verify":
		err = cmdVerify(args)
	case "import":
		err = cmdImport(args)
	case "export":
		err = cmdExport(args)
	case "list":
		err = cmdList(args)
	case "version", "--version", "-v":
		fmt.Println("lava-tickets " + version)
		return
	case "help", "-h", "--help":
		usage(os.Stdout)
		return
	default:
		fmt.Fprintf(os.Stderr, "lava-tickets: unknown subcommand %q\n\n", cmd)
		usage(os.Stderr)
		os.Exit(2)
	}

	if err != nil {
		fmt.Fprintln(os.Stderr, "lava-tickets: "+err.Error())
		// exit code 3 is reserved for export pdf/docx tool-absence (operator-actionable);
		// cmdExport sets it explicitly via os.Exit, so any error reaching here is exit 1.
		os.Exit(1)
	}
}

func usage(w *os.File) {
	fmt.Fprint(w, `lava-tickets — LVA workable-items ticket system (HelixConstitution §11.4.93/95/106)

USAGE:
  lava-tickets <subcommand> [flags]

SUBCOMMANDS:
  init     [--db PATH]                          Create/seed the SQLite DB from the schema.
  add      --title T --type T [flags]           Add a ticket; prints the new LVA-N id.
  update   --id LVA-N [field=value ...]         Update fields on a ticket.
  close    --id LVA-N --closure-status S [..]   Close a ticket (type-aware §11.4.33).
  reopen   --id LVA-N --why W --who O --when T --incident I   Reopen (§11.4.34 attribution).
  gen      [--db PATH] [--out DIR]              Generate the 4 markdown trackers from the DB.
  verify   [--db PATH] [--out DIR]              Round-trip check: regen + byte-compare on-disk md.
  import   [--db PATH] [--in DIR]               Reconstruct DB rows from the markdown trackers.
  export   --format html|pdf|docx [--out DIR]   Export trackers (html pure-Go; pdf/docx via pandoc-in-container).
  list     [--db PATH] [--status S]            Print tickets (debug helper).
  version                                       Print version.

FLAGS (add/update/close/reopen):
  --title, --body, --type, --status, --closure-status, --priority,
  --source, --source-ref, --fix-commit, --validation-test, --challenge-test,
  --closure-log, --operator-blocked-details, --duplicate-of,
  --why, --who, --when, --incident

DEFAULTS:
  --db   docs/tickets/tickets.db
  --out  docs/tickets         (markdown trackers live at this dir per the design)

The DB is the single source of truth; the markdown files are derived. The .db is
TRACKED in git per §11.4.95 (do NOT gitignore it).
`)
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

const defaultDB = "docs/tickets/tickets.db"
const defaultOut = "docs/tickets"

// parseFlags is a tiny long-flag parser supporting "--flag value" and
// "field=value" positional pairs. It is deliberately minimal (no third-party
// dep) and rejects unknown bareword tokens for safety.
func parseFlags(args []string) (map[string]string, error) {
	out := map[string]string{}
	i := 0
	for i < len(args) {
		a := args[i]
		switch {
		case strings.HasPrefix(a, "--"):
			key := strings.TrimPrefix(a, "--")
			if eq := strings.Index(key, "="); eq >= 0 {
				out[key[:eq]] = key[eq+1:]
				i++
				continue
			}
			if i+1 >= len(args) {
				return nil, fmt.Errorf("flag --%s needs a value", key)
			}
			out[key] = args[i+1]
			i += 2
		case strings.Contains(a, "="):
			eq := strings.Index(a, "=")
			out[a[:eq]] = a[eq+1:]
			i++
		default:
			return nil, fmt.Errorf("unexpected argument %q (use --flag value or field=value)", a)
		}
	}
	return out, nil
}

func dbPath(f map[string]string) string {
	if v, ok := f["db"]; ok && v != "" {
		return v
	}
	return defaultDB
}

func outDir(f map[string]string) string {
	if v, ok := f["out"]; ok && v != "" {
		return v
	}
	return defaultOut
}

func openDB(path string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", path+"?_pragma=foreign_keys(1)")
	if err != nil {
		return nil, err
	}
	if _, err := db.Exec("PRAGMA foreign_keys = ON;"); err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}

func mustExist(path string) error {
	if _, err := os.Stat(path); err != nil {
		return fmt.Errorf("database %s not found — run `lava-tickets init` first", path)
	}
	return nil
}

func ensureDir(dir string) error {
	return os.MkdirAll(dir, 0o755)
}

func absSchemaPath(dbFile string) string {
	// schema.sql lives next to the design at docs/tickets/schema.sql; default db
	// is docs/tickets/tickets.db, so the schema is in the same dir.
	return filepath.Join(filepath.Dir(dbFile), "schema.sql")
}
