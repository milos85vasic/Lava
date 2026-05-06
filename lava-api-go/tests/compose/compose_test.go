// Package compose holds parse-only tests for the SP-2 Phase 11 infrastructure
// configs:
//   - the root docker-compose.yml (5 profiles)
//   - lava-api-go/docker/observability/*.yml|yaml (Prometheus, Loki,
//     Promtail, Tempo)
//   - lava-api-go/docker/observability/grafana/provisioning/**.yml
//   - lava-api-go/docker/observability/grafana/dashboards/lava-api.json
//
// These never run a container — they only validate that each file parses via
// stdlib + yaml.v3. The Phase 14 acceptance gate brings up the actual stack
// against these same files for end-user verification.
package compose

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"gopkg.in/yaml.v3"
)

// repoRoot returns the absolute path to the Lava repo root by walking up
// from this test file.
func repoRoot(t *testing.T) string {
	t.Helper()
	_, here, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	// here = .../lava-api-go/tests/compose/compose_test.go
	root := filepath.Clean(filepath.Join(filepath.Dir(here), "..", "..", ".."))
	if _, err := os.Stat(filepath.Join(root, "docker-compose.yml")); err != nil {
		t.Fatalf("repoRoot autodetect failed: %v (computed %q)", err, root)
	}
	return root
}

func TestComposeFileParses(t *testing.T) {
	root := repoRoot(t)
	b, err := os.ReadFile(filepath.Join(root, "docker-compose.yml"))
	if err != nil {
		t.Fatal(err)
	}
	var doc map[string]any
	if err := yaml.Unmarshal(b, &doc); err != nil {
		t.Fatalf("docker-compose.yml is not valid YAML: %v", err)
	}
	services, ok := doc["services"].(map[string]any)
	if !ok || len(services) == 0 {
		t.Fatalf("expected non-empty services map, got %#v", doc["services"])
	}
	// Spot-check that all current-active services are reachable.
	// Post-Ktor cleanup (lava-api-go 2.0.12, commit a00b28f) removed
	// lava-proxy and the legacy/both profiles.
	mustContain := []string{
		"lava-postgres", "lava-migrate", "lava-api-go", // api-go profile
		"lava-prometheus", "lava-loki", // observability profile
		"lava-promtail", "lava-tempo", "lava-grafana",
		"lava-swagger-ui", // dev-docs profile
	}
	for _, name := range mustContain {
		if _, ok := services[name].(map[string]any); !ok {
			t.Fatalf("docker-compose.yml is missing service %q", name)
		}
	}
}

func TestComposeProfilesPresent(t *testing.T) {
	root := repoRoot(t)
	b, err := os.ReadFile(filepath.Join(root, "docker-compose.yml"))
	if err != nil {
		t.Fatal(err)
	}
	var doc map[string]any
	if err := yaml.Unmarshal(b, &doc); err != nil {
		t.Fatal(err)
	}
	services := doc["services"].(map[string]any)

	// Post-Ktor cleanup (commit a00b28f) removed lava-proxy + legacy/both
	// profiles. Remaining services use api-go (default), observability,
	// dev-docs profiles.
	expected := map[string][]string{
		"lava-postgres":   {"api-go", "both"},
		"lava-api-go":     {"api-go", "both"},
		"lava-prometheus": {"observability"},
		"lava-swagger-ui": {"dev-docs"},
	}
	// Anti-regression: lava-proxy MUST NOT be re-introduced unless the
	// Ktor proxy comes back; if you see this fail because lava-proxy
	// reappeared, that's a constitutional rollback.
	if _, ok := services["lava-proxy"]; ok {
		t.Errorf("lava-proxy service re-introduced (Ktor proxy was removed in lava-api-go 2.0.12)")
	}
	for svc, want := range expected {
		raw := services[svc].(map[string]any)
		got, _ := raw["profiles"].([]any)
		if len(got) != len(want) {
			t.Fatalf("service %q profiles: want %v, got %v", svc, want, got)
		}
		for i := range want {
			if got[i].(string) != want[i] {
				t.Fatalf("service %q profile[%d]: want %q, got %q",
					svc, i, want[i], got[i])
			}
		}
	}
}

func TestObservabilityYAMLConfigsParse(t *testing.T) {
	root := repoRoot(t)
	files := []string{
		"lava-api-go/docker/observability/prometheus.yml",
		"lava-api-go/docker/observability/loki-config.yaml",
		"lava-api-go/docker/observability/promtail-config.yaml",
		"lava-api-go/docker/observability/tempo.yaml",
		"lava-api-go/docker/observability/grafana/provisioning/datasources/datasources.yml",
		"lava-api-go/docker/observability/grafana/provisioning/dashboards/dashboards.yml",
	}
	for _, rel := range files {
		t.Run(rel, func(t *testing.T) {
			b, err := os.ReadFile(filepath.Join(root, rel))
			if err != nil {
				t.Fatalf("read %s: %v", rel, err)
			}
			var doc any
			if err := yaml.Unmarshal(b, &doc); err != nil {
				t.Fatalf("%s is not valid YAML: %v", rel, err)
			}
			if doc == nil {
				t.Fatalf("%s parsed to nil", rel)
			}
		})
	}
}

// TestGrafanaDashboardHasFourPanels is the falsifiability rehearsal anchor
// for Phase 11. The primary user-visible signal: when an operator opens the
// "Lava API (Go)" dashboard in Grafana, exactly four panels are rendered
// (request rate, p99 latency, cache outcomes, rutracker upstream errors).
// Mutating the JSON to remove a panel makes this test fail with a clear
// message, mechanically catching a config-drift defect.
func TestGrafanaDashboardHasFourPanels(t *testing.T) {
	root := repoRoot(t)
	b, err := os.ReadFile(filepath.Join(root, "lava-api-go/docker/observability/grafana/dashboards/lava-api.json"))
	if err != nil {
		t.Fatal(err)
	}
	var d struct {
		Title  string            `json:"title"`
		UID    string            `json:"uid"`
		Panels []json.RawMessage `json:"panels"`
	}
	if err := json.Unmarshal(b, &d); err != nil {
		t.Fatalf("lava-api.json is not valid JSON: %v", err)
	}
	if d.Title == "" || d.UID == "" {
		t.Fatalf("dashboard missing title/uid: title=%q uid=%q", d.Title, d.UID)
	}
	if len(d.Panels) != 4 {
		t.Fatalf("expected 4 panels, got %d", len(d.Panels))
	}
}

// TestGrafanaDashboardPanelTitles asserts the four panels carry the titles
// the operator expects to see — primary user-visible state again. Renaming
// or losing a panel's title (e.g. due to a copy-paste during dashboard
// editing) trips this test.
func TestGrafanaDashboardPanelTitles(t *testing.T) {
	root := repoRoot(t)
	b, err := os.ReadFile(filepath.Join(root, "lava-api-go/docker/observability/grafana/dashboards/lava-api.json"))
	if err != nil {
		t.Fatal(err)
	}
	var d struct {
		Panels []struct {
			Title string `json:"title"`
		} `json:"panels"`
	}
	if err := json.Unmarshal(b, &d); err != nil {
		t.Fatal(err)
	}
	want := []string{
		"HTTP request rate (per route)",
		"HTTP latency p99 (per route)",
		"Cache outcome breakdown",
		"Rutracker upstream errors",
	}
	if len(d.Panels) != len(want) {
		t.Fatalf("expected %d panels, got %d", len(want), len(d.Panels))
	}
	for i, p := range d.Panels {
		if p.Title != want[i] {
			t.Fatalf("panel[%d] title: want %q, got %q", i, want[i], p.Title)
		}
	}
}
