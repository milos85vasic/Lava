// Package contract — §6.A field-name parity contract.
//
// TestAuthFieldName_NoLiteralInProductionGoSource asserts the
// LAVA_AUTH_FIELD_NAME value declared in .env.example does NOT appear
// as a string literal anywhere under lava-api-go/internal/. The
// middleware must read it from cfg.AuthFieldName, not hardcode it.
//
// Forensic anchor: §6.A clauses 1–4 require every binary contract
// (the .env.example schema is one such contract — it documents the
// runtime-configurable surface) to have a falsifiable test that
// rejects the historical bug shape. Hardcoding the field name in
// production source would silently shadow whatever value an
// operator sets in their .env, which is a §6.R / §6.A double
// violation.
package contract

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

func TestAuthFieldName_NoLiteralInProductionGoSource(t *testing.T) {
	body, err := os.ReadFile(repoRootRelative(t, ".env.example"))
	if err != nil {
		t.Fatalf("read .env.example: %v", err)
	}
	re := regexp.MustCompile(`(?m)^LAVA_AUTH_FIELD_NAME=(\S+)`)
	match := re.FindStringSubmatch(string(body))
	if len(match) != 2 {
		t.Fatal("LAVA_AUTH_FIELD_NAME not declared in .env.example")
	}
	fieldName := match[1]

	internalDir := repoRootRelative(t, "lava-api-go/internal")
	violations := []string{}
	walkErr := filepath.Walk(internalDir, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}
		// Only Go source, excluding tests
		if !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
			return nil
		}
		bodyStr, _ := os.ReadFile(path)
		if strings.Contains(string(bodyStr), `"`+fieldName+`"`) {
			violations = append(violations, path)
		}
		return nil
	})
	if walkErr != nil {
		t.Fatalf("walk: %v", walkErr)
	}
	if len(violations) > 0 {
		t.Fatalf("§6.R violation: literal %q found in production source:\n  %s",
			fieldName, strings.Join(violations, "\n  "))
	}
}

// repoRootRelative resolves a path relative to the lava-api-go module
// root. Tests can be invoked from any CWD; the test data layout is
// stable.
func repoRootRelative(t *testing.T, suffix string) string {
	t.Helper()
	cwd, _ := os.Getwd()
	for {
		// .env.example is at the Lava repo root, two levels above
		// lava-api-go/tests/contract/ — walk up.
		if _, err := os.Stat(filepath.Join(cwd, "lava-api-go", "go.mod")); err == nil {
			return filepath.Join(cwd, suffix)
		}
		parent := filepath.Dir(cwd)
		if parent == cwd {
			t.Fatalf("could not find Lava repo root from %s", cwd)
		}
		cwd = parent
	}
}
