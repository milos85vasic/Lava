package main

import (
	"database/sql"
	"fmt"
	"os"
	"sort"
	"strings"
)

// cmdInit creates the DB and applies docs/tickets/schema.sql.
func cmdInit(args []string) error {
	f, err := parseFlags(args)
	if err != nil {
		return err
	}
	path := dbPath(f)
	if err := ensureDir(dirOf(path)); err != nil {
		return err
	}
	schemaFile := absSchemaPath(path)
	schema, err := os.ReadFile(schemaFile)
	if err != nil {
		return fmt.Errorf("reading schema %s: %w", schemaFile, err)
	}
	db, err := openDB(path)
	if err != nil {
		return err
	}
	defer db.Close()
	if _, err := db.Exec(string(schema)); err != nil {
		return fmt.Errorf("applying schema: %w", err)
	}
	fmt.Printf("initialized %s (schema %s applied)\n", path, schemaFile)
	return nil
}

// cmdAdd inserts a new ticket and prints its LVA-N id.
func cmdAdd(args []string) error {
	f, err := parseFlags(args)
	if err != nil {
		return err
	}
	path := dbPath(f)
	if err := mustExist(path); err != nil {
		return err
	}
	title := f["title"]
	typ := f["type"]
	if title == "" || typ == "" {
		return fmt.Errorf("add requires --title and --type")
	}
	db, err := openDB(path)
	if err != nil {
		return err
	}
	defer db.Close()

	cols := []string{"title", "type"}
	vals := []any{title, typ}
	add := func(flag, col string) {
		if v, ok := f[flag]; ok && v != "" {
			cols = append(cols, col)
			vals = append(vals, v)
		}
	}
	add("body", "body")
	add("status", "status")
	add("closure-status", "closure_status")
	add("priority", "priority")
	add("source", "source")
	add("source-ref", "source_ref")
	add("fix-commit", "fix_commit_sha")
	add("validation-test", "validation_test")
	add("challenge-test", "challenge_test")
	add("closure-log", "closure_log")
	add("operator-blocked-details", "operator_blocked_details")
	add("duplicate-of", "duplicate_of")
	add("why", "reopened_why")
	add("who", "reopened_who")
	add("when", "reopened_when")
	add("incident", "reopened_incident")

	ph := make([]string, len(cols))
	for i := range ph {
		ph[i] = "?"
	}
	q := fmt.Sprintf("INSERT INTO tickets (%s) VALUES (%s)",
		strings.Join(cols, ", "), strings.Join(ph, ", "))
	res, err := db.Exec(q, vals...)
	if err != nil {
		return fmt.Errorf("insert: %w", err)
	}
	seq, _ := res.LastInsertId()
	id := fmt.Sprintf("LVA-%d", seq)
	fmt.Println(id)
	return nil
}

// flagToCol maps an --update flag name to its DB column.
var flagToCol = map[string]string{
	"title":                    "title",
	"body":                     "body",
	"type":                     "type",
	"status":                   "status",
	"closure-status":           "closure_status",
	"priority":                 "priority",
	"source":                   "source",
	"source-ref":               "source_ref",
	"fix-commit":               "fix_commit_sha",
	"validation-test":          "validation_test",
	"challenge-test":           "challenge_test",
	"closure-log":              "closure_log",
	"operator-blocked-details": "operator_blocked_details",
	"duplicate-of":             "duplicate_of",
	"why":                      "reopened_why",
	"who":                      "reopened_who",
	"when":                     "reopened_when",
	"incident":                 "reopened_incident",
}

// cmdUpdate updates arbitrary fields on a ticket.
func cmdUpdate(args []string) error {
	f, err := parseFlags(args)
	if err != nil {
		return err
	}
	path := dbPath(f)
	if err := mustExist(path); err != nil {
		return err
	}
	id := f["id"]
	if id == "" {
		return fmt.Errorf("update requires --id LVA-N")
	}
	db, err := openDB(path)
	if err != nil {
		return err
	}
	defer db.Close()
	return applyUpdate(db, id, f, nil)
}

// applyUpdate builds + executes an UPDATE for every recognized field flag set.
// extra forces specific column=value pairs (used by close/reopen).
func applyUpdate(db *sql.DB, id string, f map[string]string, extra map[string]string) error {
	set := []string{}
	vals := []any{}
	// Deterministic ordering for stable SQL (helps debugging).
	flags := make([]string, 0, len(f))
	for k := range f {
		flags = append(flags, k)
	}
	sort.Strings(flags)
	for _, k := range flags {
		col, ok := flagToCol[k]
		if !ok {
			continue
		}
		set = append(set, col+" = ?")
		vals = append(vals, f[k])
	}
	for col, v := range extra {
		set = append(set, col+" = ?")
		vals = append(vals, v)
	}
	if len(set) == 0 {
		return fmt.Errorf("update %s: no recognized fields to set", id)
	}
	vals = append(vals, id)
	q := fmt.Sprintf("UPDATE tickets SET %s WHERE id = ?", strings.Join(set, ", "))
	res, err := db.Exec(q, vals...)
	if err != nil {
		return fmt.Errorf("update %s: %w", id, err)
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return fmt.Errorf("update %s: no such ticket", id)
	}
	fmt.Printf("updated %s\n", id)
	return nil
}

// cmdClose closes a ticket with a type-aware closure_status (§11.4.33 trigger
// enforces validity).
func cmdClose(args []string) error {
	f, err := parseFlags(args)
	if err != nil {
		return err
	}
	path := dbPath(f)
	if err := mustExist(path); err != nil {
		return err
	}
	id := f["id"]
	cs := f["closure-status"]
	if id == "" || cs == "" {
		return fmt.Errorf("close requires --id LVA-N and --closure-status S")
	}
	db, err := openDB(path)
	if err != nil {
		return err
	}
	defer db.Close()
	// Set closure_status first (so the BEFORE UPDATE trigger sees it), then status.
	if _, err := db.Exec("UPDATE tickets SET closure_status = ? WHERE id = ?", cs, id); err != nil {
		return fmt.Errorf("close %s set closure_status: %w", id, err)
	}
	// Apply any extra evidence flags + the terminal status in one update so the
	// §11.4.33 trigger validates against the final row.
	extra := map[string]string{"status": "Closed", "closure_status": cs}
	// Remove status/closure-status from f so applyUpdate doesn't double-set.
	delete(f, "status")
	delete(f, "closure-status")
	return applyUpdate(db, id, f, extra)
}

// cmdReopen reopens a closed ticket with full §11.4.34 attribution.
func cmdReopen(args []string) error {
	f, err := parseFlags(args)
	if err != nil {
		return err
	}
	path := dbPath(f)
	if err := mustExist(path); err != nil {
		return err
	}
	id := f["id"]
	if id == "" {
		return fmt.Errorf("reopen requires --id LVA-N")
	}
	if f["why"] == "" || f["who"] == "" || f["when"] == "" || f["incident"] == "" {
		return fmt.Errorf("§11.4.34: reopen requires --why --who --when --incident")
	}
	db, err := openDB(path)
	if err != nil {
		return err
	}
	defer db.Close()
	// Set attribution columns first, then flip status to Reopened so the trigger
	// sees the populated attribution.
	if _, err := db.Exec(
		"UPDATE tickets SET reopened_why=?, reopened_who=?, reopened_when=?, reopened_incident=? WHERE id=?",
		f["why"], f["who"], f["when"], f["incident"], id); err != nil {
		return fmt.Errorf("reopen %s set attribution: %w", id, err)
	}
	res, err := db.Exec("UPDATE tickets SET status='Reopened' WHERE id=?", id)
	if err != nil {
		return fmt.Errorf("reopen %s: %w", id, err)
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return fmt.Errorf("reopen %s: no such ticket", id)
	}
	fmt.Printf("reopened %s\n", id)
	return nil
}

// cmdList prints a quick table (debug helper).
func cmdList(args []string) error {
	f, err := parseFlags(args)
	if err != nil {
		return err
	}
	path := dbPath(f)
	if err := mustExist(path); err != nil {
		return err
	}
	db, err := openDB(path)
	if err != nil {
		return err
	}
	defer db.Close()
	q := "SELECT id, type, status, COALESCE(closure_status,''), priority, title FROM tickets"
	var vals []any
	if s := f["status"]; s != "" {
		q += " WHERE status = ?"
		vals = append(vals, s)
	}
	q += " ORDER BY seq"
	rows, err := db.Query(q, vals...)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var id, typ, st, cs, pr, title string
		if err := rows.Scan(&id, &typ, &st, &cs, &pr, &title); err != nil {
			return err
		}
		fmt.Printf("%-8s %-9s %-15s %-12s %-3s %s\n", id, typ, st, cs, pr, title)
	}
	return rows.Err()
}

func dirOf(p string) string {
	i := strings.LastIndex(p, "/")
	if i < 0 {
		return "."
	}
	return p[:i]
}
