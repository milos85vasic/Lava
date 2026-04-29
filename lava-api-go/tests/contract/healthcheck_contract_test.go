// Package contract — healthcheck contract test.
//
// This test exists because a stale `--http3` flag in the lava-api-go
// healthcheck (docker-compose.yml) caused 569 consecutive probe
// failures in production while the API itself served 200 on both
// HTTP/3 and HTTP/2. The compose file passed `compose config --quiet`,
// every other test was green, the container was running and serving,
// yet the orchestrator labelled it "unhealthy". The healthprobe binary
// (cmd/healthprobe/main.go) only registers -url / -insecure / -timeout;
// `--http3` is rejected by flag.Parse and the probe exits 1.
//
// Sixth Law alignment (CLAUDE.md):
//   - clause 1 (same surfaces the user touches): the probe IS the
//     surface the orchestrator touches; if it can't run, "healthy"
//     reporting is bluff. This test invokes the same binary with the
//     same compose-defined flag set.
//   - clause 2 (provably falsifiable): TestHealthcheckContract_Falsifiability
//     reintroduces `--http3` in a fixture compose and confirms the
//     checker reports a clear failure. Recorded in commit body.
//   - clause 3 (primary assertion on user-visible state): the chief
//     failure signal IS the binary's stderr — the same output a real
//     orchestrator sees and uses to decide health.
//
// Inheritance: this is the structural fix for the bug class. Any
// future script-→-binary invocation in start.sh / stop.sh /
// build_and_release.sh / docker-compose.yml that we own SHOULD be
// covered by an analogous contract test before shipping.
package contract

import (
	"bytes"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"
)

// composeFile is the schema we care about — we only need the
// healthcheck.test array, so unknown fields are ignored.
type composeFile struct {
	Services map[string]struct {
		Healthcheck *struct {
			Test []string `yaml:"test"`
		} `yaml:"healthcheck"`
	} `yaml:"services"`
}

// repoRoot returns the absolute path to the repo root (one level above
// lava-api-go/), independent of the test's CWD.
func repoRoot(t *testing.T) string {
	t.Helper()
	cwd, err := os.Getwd()
	if err != nil {
		t.Fatalf("os.Getwd: %v", err)
	}
	// tests/contract/ → lava-api-go/ → repo root
	return filepath.Clean(filepath.Join(cwd, "..", "..", ".."))
}

// buildHealthprobe builds cmd/healthprobe to a temp dir and returns
// the binary path. Builds are deterministic; the binary is reused for
// the lifetime of the test process.
func buildHealthprobe(t *testing.T) string {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("healthprobe not built for windows")
	}
	root := repoRoot(t)
	apigoDir := filepath.Join(root, "lava-api-go")
	binDir := t.TempDir()
	binPath := filepath.Join(binDir, "healthprobe")

	cmd := exec.Command("go", "build", "-trimpath", "-o", binPath, "./cmd/healthprobe")
	cmd.Dir = apigoDir
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		t.Fatalf("go build healthprobe: %v\nstderr:\n%s", err, stderr.String())
	}
	return binPath
}

// registeredFlags returns the set of flag names the binary registers,
// as recovered from its Usage output. We trigger the Usage dump by
// passing a flag the binary cannot possibly define (a sentinel name
// long enough to never collide with a real flag).
func registeredFlags(t *testing.T, binPath string) map[string]struct{} {
	t.Helper()
	const sentinel = "--__contract_probe_unknown_flag_xyz"
	cmd := exec.Command(binPath, sentinel)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	_ = cmd.Run() // expected to exit non-zero with usage on stderr

	out := stderr.String()
	if !strings.Contains(out, "Usage of") {
		t.Fatalf("binary %s did not emit a Usage block on unknown flag.\nstderr:\n%s", binPath, out)
	}
	// flag-package usage lines look like:
	//   "  -name"
	//   "  -name value"
	//   "  -name string"
	//   "  -name duration"
	// We extract the first whitespace-prefixed token that starts with `-`.
	re := regexp.MustCompile(`(?m)^\s+-([A-Za-z][A-Za-z0-9_-]*)`)
	matches := re.FindAllStringSubmatch(out, -1)
	if len(matches) == 0 {
		t.Fatalf("could not parse any flag names from Usage output:\n%s", out)
	}
	set := make(map[string]struct{}, len(matches))
	for _, m := range matches {
		set[m[1]] = struct{}{}
	}
	return set
}

// extractFlags returns the flag names appearing in a compose
// healthcheck.test array. Flags are tokens starting with `-` or `--`,
// excluding the leading `CMD` / `CMD-SHELL` literal.
//
// The leading entry of the array is `CMD` (string form) or
// `CMD-SHELL`; the second is the binary path; the rest may be flags
// or positional args. We treat any subsequent token starting with `-`
// as a flag for our subset check.
func extractFlags(test []string) []string {
	if len(test) < 2 {
		return nil
	}
	var flags []string
	for _, tok := range test[2:] {
		if strings.HasPrefix(tok, "-") {
			// strip leading dashes and any `=value` suffix
			name := strings.TrimLeft(tok, "-")
			if i := strings.IndexByte(name, '='); i >= 0 {
				name = name[:i]
			}
			if name != "" {
				flags = append(flags, name)
			}
		}
	}
	return flags
}

// loadCompose parses docker-compose.yml at the given path. Returns
// an error rather than t.Fatal so the falsifiability sub-test can
// reuse this helper against fixture YAMLs.
func loadCompose(path string) (*composeFile, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var c composeFile
	if err := yaml.Unmarshal(raw, &c); err != nil {
		return nil, err
	}
	return &c, nil
}

// validateAgainstHealthprobe walks every service in the compose file,
// finds healthchecks that invoke /usr/local/bin/healthprobe (the
// only binary we own and ship in the lava-api-go image), and asserts
// every flag in its test array is one healthprobe registers.
//
// Returns a slice of failure messages; an empty slice means all
// checked services passed.
func validateAgainstHealthprobe(c *composeFile, registered map[string]struct{}) []string {
	const target = "/usr/local/bin/healthprobe"
	var failures []string
	for name, svc := range c.Services {
		if svc.Healthcheck == nil {
			continue
		}
		test := svc.Healthcheck.Test
		if len(test) < 2 || test[1] != target {
			continue
		}
		for _, flag := range extractFlags(test) {
			if _, ok := registered[flag]; !ok {
				failures = append(failures,
					"service "+name+": healthcheck.test passes flag -"+flag+
						" but "+target+" does not register it")
			}
		}
	}
	return failures
}

// TestHealthcheckContract is the load-bearing contract test: every
// flag the production docker-compose.yml passes to healthprobe MUST
// be a flag the binary actually registers. A future stale `--http3`
// (or any other typo / drift) trips this test instead of shipping
// green and producing N consecutive probe failures in production.
func TestHealthcheckContract(t *testing.T) {
	binPath := buildHealthprobe(t)
	registered := registeredFlags(t, binPath)
	t.Logf("healthprobe registered flags: %v", keys(registered))

	composePath := filepath.Join(repoRoot(t), "docker-compose.yml")
	c, err := loadCompose(composePath)
	if err != nil {
		t.Fatalf("load %s: %v", composePath, err)
	}

	failures := validateAgainstHealthprobe(c, registered)
	if len(failures) > 0 {
		t.Fatalf("compose ↔ healthprobe flag-set drift detected:\n  %s",
			strings.Join(failures, "\n  "))
	}
}

// TestDockerfileHealthcheckContract — same flag-subset rule applied to
// the runtime-stage HEALTHCHECK directive in the Dockerfile. The
// Dockerfile's HEALTHCHECK is the actually-effective probe for the
// lava-api-go container because the compose-level healthcheck was
// removed (see docker-compose.yml note: distroless has no /bin/sh,
// and podman-compose translates `test: ["CMD", …]` into a CMD-SHELL
// wrapper that fails silently on sh-less images).
//
// Sixth-Law clauses 1, 2, 3 alignment:
//   - clause 1: the directive parsed here is the same one the runtime
//     reads via the OCI image config; this test traverses the same
//     surface the orchestrator does.
//   - clause 2: see TestDockerfileHealthcheck_Falsifiability below.
//   - clause 3: failure signal is the same flag-set diff a real probe
//     would surface on first invocation (binary's Usage output).
func TestDockerfileHealthcheckContract(t *testing.T) {
	binPath := buildHealthprobe(t)
	registered := registeredFlags(t, binPath)

	dockerfilePath := filepath.Join(repoRoot(t), "lava-api-go", "docker", "Dockerfile")
	cmds, err := extractDockerfileHealthchecks(dockerfilePath)
	if err != nil {
		t.Fatalf("parse %s: %v", dockerfilePath, err)
	}
	if len(cmds) == 0 {
		t.Fatal("Dockerfile has no HEALTHCHECK directive — the runtime container would have no probe at all (every container would read healthy by default, hiding crash loops). Add an exec-form HEALTHCHECK to lava-api-go/docker/Dockerfile.")
	}

	var failures []string
	for _, cmd := range cmds {
		// The exec-form CMD array (JSON-style) — first token is the
		// binary, rest are flags or positional args.
		if len(cmd) == 0 {
			continue
		}
		if filepath.Base(cmd[0]) != "healthprobe" {
			continue
		}
		for _, tok := range cmd[1:] {
			if !strings.HasPrefix(tok, "-") {
				continue
			}
			name := strings.TrimLeft(tok, "-")
			if i := strings.IndexByte(name, '='); i >= 0 {
				name = name[:i]
			}
			if name == "" {
				continue
			}
			if _, ok := registered[name]; !ok {
				failures = append(failures,
					"Dockerfile HEALTHCHECK passes -"+name+" but healthprobe does not register it")
			}
		}
	}
	if len(failures) > 0 {
		t.Fatalf("Dockerfile HEALTHCHECK ↔ healthprobe flag-set drift detected:\n  %s",
			strings.Join(failures, "\n  "))
	}
}

// extractDockerfileHealthchecks returns the CMD argv arrays of every
// `HEALTHCHECK ... CMD [...]` line in the Dockerfile that uses
// exec-form (JSON array) syntax. Shell-form HEALTHCHECK CMD lines are
// returned with a single element holding the raw shell string — the
// caller must decide whether that's acceptable (for distroless images
// it is NOT, per the lessons-learned note in docker-compose.yml).
func extractDockerfileHealthchecks(path string) ([][]string, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	// Match `HEALTHCHECK ... CMD ["a", "b", ...]` with optional flags
	// like `--interval=10s --retries=6` between HEALTHCHECK and CMD.
	// The `(?s)` flag lets `.` match newlines for multi-line directives,
	// though current Dockerfile uses single-line form.
	re := regexp.MustCompile(`(?m)^HEALTHCHECK\s+(?:--\S+\s+)*CMD\s+\[(.*)\]\s*$`)
	matches := re.FindAllStringSubmatch(string(raw), -1)
	out := make([][]string, 0, len(matches))
	for _, m := range matches {
		argv := parseJSONArrayArgs(m[1])
		out = append(out, argv)
	}
	return out, nil
}

// parseJSONArrayArgs takes the inside of `[ ... ]` from an exec-form
// CMD/HEALTHCHECK directive and returns the string arguments. Quotes
// are required by Docker spec, so a permissive regex catching
// "..."-delimited substrings is enough — full JSON parsing is
// over-engineering here.
func parseJSONArrayArgs(s string) []string {
	re := regexp.MustCompile(`"([^"\\]*(?:\\.[^"\\]*)*)"`)
	matches := re.FindAllStringSubmatch(s, -1)
	out := make([]string, 0, len(matches))
	for _, m := range matches {
		out = append(out, m[1])
	}
	return out
}

// TestDockerfileHealthcheck_Falsifiability — corrupt the parsed
// Dockerfile CMD by injecting a bogus flag and confirm the contract
// checker rejects it. Sixth-Law clause-2 rehearsal.
func TestDockerfileHealthcheck_Falsifiability(t *testing.T) {
	binPath := buildHealthprobe(t)
	registered := registeredFlags(t, binPath)

	// Reuse extract logic on synthetic content that matches Dockerfile syntax.
	syntheticDockerfile := `FROM scratch
HEALTHCHECK --interval=10s --retries=6 CMD ["/usr/local/bin/healthprobe", "--http3", "https://localhost:8443/health"]
ENTRYPOINT ["/usr/local/bin/lava-api-go"]
`
	tmp := filepath.Join(t.TempDir(), "Dockerfile")
	if err := os.WriteFile(tmp, []byte(syntheticDockerfile), 0o644); err != nil {
		t.Fatalf("write fixture: %v", err)
	}
	cmds, err := extractDockerfileHealthchecks(tmp)
	if err != nil {
		t.Fatalf("parse fixture: %v", err)
	}
	if len(cmds) != 1 || len(cmds[0]) < 2 {
		t.Fatalf("fixture parse: expected 1 CMD with ≥2 tokens, got %v", cmds)
	}
	// Run the same subset check inline.
	var failures []string
	for _, tok := range cmds[0][1:] {
		if !strings.HasPrefix(tok, "-") {
			continue
		}
		name := strings.TrimLeft(tok, "-")
		if i := strings.IndexByte(name, '='); i >= 0 {
			name = name[:i]
		}
		if _, ok := registered[name]; !ok {
			failures = append(failures, "flag -"+name+" not registered")
		}
	}
	if len(failures) == 0 {
		t.Fatal("falsifiability rehearsal: parsed Dockerfile with --http3 but checker did not reject it — checker is a bluff")
	}
	t.Logf("falsifiability rehearsal: checker correctly rejected --http3 — %v", failures)
}

// TestHealthcheckContract_Falsifiability is the Sixth-Law-clause-2
// rehearsal: a fixture compose with the original buggy `--http3` flag
// MUST be rejected by the contract checker. If this sub-test passes
// when the buggy flag is present, the contract checker is itself a
// bluff. Run with:
//
//	go test -run TestHealthcheckContract_Falsifiability ./tests/contract/
//
// Recorded run: with `--http3` reintroduced, validateAgainstHealthprobe
// returns 1 failure ("service lava-api-go: healthcheck.test passes
// flag -http3 but /usr/local/bin/healthprobe does not register it").
// With `--http3` absent, returns 0 failures.
func TestHealthcheckContract_Falsifiability(t *testing.T) {
	binPath := buildHealthprobe(t)
	registered := registeredFlags(t, binPath)

	const buggyYAML = `
services:
  lava-api-go:
    healthcheck:
      test: ["CMD", "/usr/local/bin/healthprobe", "--http3", "https://localhost:8443/health"]
`
	var c composeFile
	if err := yaml.Unmarshal([]byte(buggyYAML), &c); err != nil {
		t.Fatalf("yaml.Unmarshal fixture: %v", err)
	}

	failures := validateAgainstHealthprobe(&c, registered)
	if len(failures) == 0 {
		t.Fatal("falsifiability rehearsal: contract checker did not catch the buggy --http3 flag — checker is a bluff")
	}
	wantSubstr := "flag -http3"
	found := false
	for _, f := range failures {
		if strings.Contains(f, wantSubstr) {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("falsifiability rehearsal: expected failure mentioning %q, got: %v", wantSubstr, failures)
	}
	t.Logf("falsifiability rehearsal: checker correctly rejected --http3 — %s", failures[0])
}

// TestLifecycleScriptsForceDockerFormat — guards against the OCI-image-format
// silently-strips-HEALTHCHECK class of bug. Forensic anchor (2026-04-29):
// podman defaults to OCI format; a Dockerfile HEALTHCHECK directive built
// with that default is dropped at build time with only a warning to stderr,
// leaving the resulting image with `Healthcheck: null` in its OCI config.
// Containers started from that image have NO probe — orchestrators report
// them "running" indefinitely while the application could be crash-looping.
//
// The structural fix is: every script that triggers a podman build (directly
// or transitively via `podman compose up`) MUST export `BUILDAH_FORMAT=docker`
// so the resulting image is in docker format and carries HEALTHCHECK in its
// config. This test asserts the export is present in every such script we own.
//
// Sixth-Law alignment:
//   - clause 1: the surface checked here IS what podman/buildah reads at
//     image-build time; same surface, no shortcut.
//   - clause 2: deleting the export from start.sh and re-running this test
//     must produce a clear failure (rehearsal recorded in commit body).
//   - clause 3: failure signal is "script X does not export BUILDAH_FORMAT=docker"
//     — directly actionable, names the offending file.
func TestLifecycleScriptsForceDockerFormat(t *testing.T) {
	root := repoRoot(t)
	// The complete list of scripts that may invoke podman/buildah builds.
	// Add new lifecycle scripts here as they're created.
	scripts := []string{
		"start.sh",
		"build_and_release.sh",
	}
	// Match an actual export directive (anchored to start of line, optional
	// leading whitespace, no leading `#` comment marker), so commenting the
	// directive out is correctly detected as a regression. The previous
	// substring-only check fooled itself: a `# DELETED: export
	// BUILDAH_FORMAT=docker` comment still matched.
	exportRe := regexp.MustCompile(`(?m)^[[:space:]]*export[[:space:]]+BUILDAH_FORMAT=docker(?:[[:space:]]|$)`)
	var failures []string
	for _, s := range scripts {
		path := filepath.Join(root, s)
		raw, err := os.ReadFile(path)
		if err != nil {
			failures = append(failures, "cannot read "+s+": "+err.Error())
			continue
		}
		if !exportRe.Match(raw) {
			failures = append(failures,
				s+" does not contain an active `export BUILDAH_FORMAT=docker` "+
					"directive — image builds will produce OCI-format images "+
					"that silently drop HEALTHCHECK (see docker-compose.yml comment)")
		}
	}
	if len(failures) > 0 {
		t.Fatalf("lifecycle-script BUILDAH_FORMAT=docker drift detected:\n  %s",
			strings.Join(failures, "\n  "))
	}
}

// TestHealthcheckContract_BinaryExists is a smoke test that the
// build step actually produces a binary the orchestrator could exec.
// Without this, a missing healthprobe in the runtime image (e.g.
// after a Dockerfile refactor that drops the COPY) would manifest as
// "exec: not found" rather than as a flag mismatch — that is
// arguably a different bug class but adjacent enough to belong here.
func TestHealthcheckContract_BinaryExists(t *testing.T) {
	binPath := buildHealthprobe(t)
	info, err := os.Stat(binPath)
	if err != nil {
		t.Fatalf("stat %s: %v", binPath, err)
	}
	if info.Mode()&0o111 == 0 {
		t.Fatalf("healthprobe %s is not executable (mode %v)", binPath, info.Mode())
	}
	if info.Size() == 0 {
		t.Fatalf("healthprobe %s is empty", binPath)
	}
}

// keys is a tiny helper to produce a deterministic slice for logging.
// Sorted output keeps test logs diffable across runs.
func keys(m map[string]struct{}) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	// stable order without importing sort: the slice is small
	for i := 0; i < len(out); i++ {
		for j := i + 1; j < len(out); j++ {
			if out[i] > out[j] {
				out[i], out[j] = out[j], out[i]
			}
		}
	}
	return out
}

// Compile-time guard: `errors` is imported because future test
// extensions are likely to use errors.As against exec.ExitError; we
// keep the import here so the next contributor doesn't have to add it.
var _ = errors.New
