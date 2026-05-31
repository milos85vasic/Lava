package main

import (
	"bytes"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// buildBinary compiles lava-tickets into a temp path. Per §6.A the contract test
// builds (or locates) the real binary and asserts against its actual surface.
func buildBinary(t *testing.T) string {
	t.Helper()
	bin := filepath.Join(t.TempDir(), "lava-tickets-test")
	cmd := exec.Command("go", "build", "-o", bin, ".")
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("go build failed: %v\n%s", err, out)
	}
	return bin
}

// schemaSrc locates the real docs/tickets/schema.sql relative to this module.
func schemaSrc(t *testing.T) string {
	t.Helper()
	// module is at tools/lava-tickets; schema is at ../../docs/tickets/schema.sql
	p, err := filepath.Abs("../../docs/tickets/schema.sql")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(p); err != nil {
		t.Fatalf("schema.sql not found at %s: %v", p, err)
	}
	return p
}

// newSeededDB sets up a temp docs/tickets layout with schema + a small seed and
// returns the work dir (containing docs/tickets/{schema.sql,tickets.db}).
func newSeededDB(t *testing.T, bin string) (work, db, ticketsDir string) {
	t.Helper()
	work = t.TempDir()
	ticketsDir = filepath.Join(work, "docs", "tickets")
	if err := os.MkdirAll(ticketsDir, 0o755); err != nil {
		t.Fatal(err)
	}
	// copy the real schema in
	sch, err := os.ReadFile(schemaSrc(t))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(ticketsDir, "schema.sql"), sch, 0o644); err != nil {
		t.Fatal(err)
	}
	db = filepath.Join(ticketsDir, "tickets.db")
	run(t, bin, "init", "--db", db)
	return work, db, ticketsDir
}

func run(t *testing.T, bin string, args ...string) string {
	t.Helper()
	out, err := exec.Command(bin, args...).CombinedOutput()
	if err != nil {
		t.Fatalf("%s %s failed: %v\n%s", bin, strings.Join(args, " "), err, out)
	}
	return string(out)
}

func runExpectFail(t *testing.T, bin string, args ...string) (string, int) {
	t.Helper()
	cmd := exec.Command(bin, args...)
	out, err := cmd.CombinedOutput()
	code := 0
	if err != nil {
		if ee, ok := err.(*exec.ExitError); ok {
			code = ee.ExitCode()
		} else {
			t.Fatalf("unexpected non-exit error: %v", err)
		}
	}
	return string(out), code
}

// TestSubcommandSurface asserts the binary's documented subcommand surface
// (§6.A clause 2/3): every subcommand the design promises is recognized, and
// unknown subcommands are rejected with exit 2.
func TestSubcommandSurface(t *testing.T) {
	bin := buildBinary(t)
	help := run(t, bin, "help")
	for _, sub := range []string{"init", "add", "update", "close", "reopen", "gen", "verify", "import", "export"} {
		if !strings.Contains(help, sub) {
			t.Errorf("help output missing documented subcommand %q", sub)
		}
	}
	// unknown subcommand → exit 2
	out, code := runExpectFail(t, bin, "bogus-subcommand")
	if code != 2 {
		t.Errorf("unknown subcommand exit = %d, want 2; output=%s", code, out)
	}
}

// TestRoundTripByteIdentical is the §11.4.106 load-bearing test: gen writes the
// trackers, verify must report byte-identical PASS. Then a deliberate edit to a
// tracker must make verify FAIL (falsifiability — §6.A clause 4).
func TestRoundTripByteIdentical(t *testing.T) {
	bin := buildBinary(t)
	_, db, dir := newSeededDB(t, bin)

	run(t, bin, "add", "--db", db, "--title", "Round trip A", "--type", "Bug", "--priority", "P1")
	run(t, bin, "add", "--db", db, "--title", "Round trip B", "--type", "Feature")
	run(t, bin, "gen", "--db", db, "--out", dir)

	got := run(t, bin, "verify", "--db", db, "--out", dir)
	if !strings.Contains(got, "§11.4.106 PASS") {
		t.Fatalf("verify did not report PASS after gen:\n%s", got)
	}

	// Falsifiability: corrupt a tracker → verify must FAIL with non-zero exit.
	issues := filepath.Join(dir, docIssues)
	orig, _ := os.ReadFile(issues)
	corrupted := append([]byte("CORRUPTED LINE\n"), orig...)
	if err := os.WriteFile(issues, corrupted, 0o644); err != nil {
		t.Fatal(err)
	}
	out, code := runExpectFail(t, bin, "verify", "--db", db, "--out", dir)
	if code == 0 {
		t.Fatalf("verify PASSED on corrupted tracker — round-trip check is a bluff! output:\n%s", out)
	}
	if !strings.Contains(out, "VERIFY FAIL") {
		t.Errorf("expected VERIFY FAIL message on corruption; got:\n%s", out)
	}
}

// TestImportReproducesRows asserts import of the generated md reconciles exactly
// with the DB rows (the inverse projection).
func TestImportReproducesRows(t *testing.T) {
	bin := buildBinary(t)
	_, db, dir := newSeededDB(t, bin)

	idBug := strings.TrimSpace(run(t, bin, "add", "--db", db, "--title", "Importable bug", "--type", "Bug"))
	run(t, bin, "add", "--db", db, "--title", "Importable task", "--type", "Task")
	// close one so it lands in Fixed.md
	run(t, bin, "close", "--db", db, "--id", idBug, "--closure-status", "Fixed", "--fix-commit", "deadbeef")
	run(t, bin, "gen", "--db", db, "--out", dir)

	out := run(t, bin, "import", "--db", db, "--in", dir)
	if !strings.Contains(out, "IMPORT PASS") {
		t.Fatalf("import did not reconcile:\n%s", out)
	}
	if !strings.Contains(out, "2 tickets") {
		t.Errorf("expected 2 reconciled tickets; got:\n%s", out)
	}
}

// TestClosureStatusTypeAware is the §11.4.33 trigger contract: a Bug cannot be
// closed with an Implemented closure_status; a Bug→Fixed succeeds.
func TestClosureStatusTypeAware(t *testing.T) {
	bin := buildBinary(t)
	_, db, _ := newSeededDB(t, bin)

	idBug := strings.TrimSpace(run(t, bin, "add", "--db", db, "--title", "Type-aware bug", "--type", "Bug"))
	// Bug + Implemented must be REJECTED by the trigger.
	out, code := runExpectFail(t, bin, "close", "--db", db, "--id", idBug, "--closure-status", "Implemented")
	if code == 0 {
		t.Fatalf("Bug→Implemented was accepted — §11.4.33 trigger is a bluff! output:\n%s", out)
	}
	if !strings.Contains(out, "11.4.33") {
		t.Errorf("expected §11.4.33 rejection message; got:\n%s", out)
	}
	// Bug + Fixed must succeed.
	ok := run(t, bin, "close", "--db", db, "--id", idBug, "--closure-status", "Fixed")
	if !strings.Contains(ok, "updated "+idBug) {
		t.Errorf("Bug→Fixed should succeed; got:\n%s", ok)
	}
}

// TestReopenAttribution is the §11.4.34 trigger contract: reopen without full
// attribution is rejected; reopen with all four is accepted.
func TestReopenAttribution(t *testing.T) {
	bin := buildBinary(t)
	_, db, _ := newSeededDB(t, bin)

	idBug := strings.TrimSpace(run(t, bin, "add", "--db", db, "--title", "Reopenable bug", "--type", "Bug"))
	run(t, bin, "close", "--db", db, "--id", idBug, "--closure-status", "Fixed")

	// Missing attribution → CLI rejects before touching DB.
	out, code := runExpectFail(t, bin, "reopen", "--db", db, "--id", idBug, "--why", "regressed")
	if code == 0 {
		t.Fatalf("reopen without full attribution accepted — §11.4.34 is a bluff! output:\n%s", out)
	}
	// Full attribution → accepted.
	ok := run(t, bin, "reopen", "--db", db, "--id", idBug,
		"--why", "test-failed", "--who", "AI", "--when", "2026-05-31T00:00:00Z", "--incident", "LVA-1")
	if !strings.Contains(ok, "reopened "+idBug) {
		t.Errorf("full-attribution reopen should succeed; got:\n%s", ok)
	}
}

// TestHTMLExportPureGo asserts the pure-Go html exporter produces real,
// well-formed HTML containing the ticket data.
func TestHTMLExportPureGo(t *testing.T) {
	bin := buildBinary(t)
	_, db, dir := newSeededDB(t, bin)
	run(t, bin, "add", "--db", db, "--title", "HtmlExportTitle", "--type", "Bug")
	run(t, bin, "gen", "--db", db, "--out", dir)

	expDir := filepath.Join(dir, "export", "html")
	run(t, bin, "export", "--db", db, "--out", dir, "--format", "html")
	html, err := os.ReadFile(filepath.Join(expDir, "Issues.html"))
	if err != nil {
		t.Fatalf("Issues.html not produced: %v", err)
	}
	if !bytes.Contains(html, []byte("<!DOCTYPE html>")) {
		t.Errorf("Issues.html missing doctype")
	}
	if !bytes.Contains(html, []byte("HtmlExportTitle")) {
		t.Errorf("Issues.html missing ticket title content")
	}
	if !bytes.Contains(html, []byte("<table>")) {
		t.Errorf("Issues.html missing rendered table")
	}
}

// TestExportPdfHonestWhenNoTool asserts the anti-bluff contract: pdf/docx export
// either succeeds via a real container OR exits non-zero with an honest message —
// it MUST NOT silently create an empty/fake file. We assert it never writes a
// zero-or-fake pdf when the tool path fails.
func TestExportPdfNeverFakes(t *testing.T) {
	bin := buildBinary(t)
	_, db, dir := newSeededDB(t, bin)
	run(t, bin, "add", "--db", db, "--title", "PdfTitle", "--type", "Bug")
	run(t, bin, "gen", "--db", db, "--out", dir)

	// Force "no runtime" by giving an empty PATH so podman/docker can't be found.
	expDir := filepath.Join(dir, "export", "pdf")
	cmd := exec.Command(bin, "export", "--db", db, "--out", dir, "--format", "pdf")
	cmd.Env = append(os.Environ(), "PATH=")
	out, err := cmd.CombinedOutput()
	code := 0
	if ee, ok := err.(*exec.ExitError); ok {
		code = ee.ExitCode()
	}
	if code == 0 {
		t.Fatalf("pdf export with no runtime returned 0 — should be non-zero honest failure. output:\n%s", out)
	}
	if !strings.Contains(string(out), "will NOT fake") {
		t.Errorf("expected honest no-fake message; got:\n%s", out)
	}
	// Assert no fake pdf was written.
	if fi, err := os.Stat(filepath.Join(expDir, "Issues.pdf")); err == nil {
		t.Errorf("a fake Issues.pdf was written (%d bytes) — §6.J violation", fi.Size())
	}
}
