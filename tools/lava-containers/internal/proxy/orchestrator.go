// Orchestrator is the profile-aware compose driver added in SP-2 Phase 11
// (Task 11.3). It sits next to the legacy Manager (which still owns the
// Ktor-proxy-specific build pipeline) and translates --profile=X plus optional
// --with-observability / --dev-docs into a single
// `<runtime> compose --profile X --profile Y up -d` invocation.
//
// TODO(SP-2 follow-up): delegate to Submodules/Containers/pkg/compose once
// it exposes profile-aware methods. Today, ComposeProject.Profile is a single
// string, so a multi-profile invocation cannot be expressed through the
// upstream API without modifying it (and that is a vasic-digital submodule
// change, not a Lava change). Until then, we shell out directly via
// runtime.Runtime.ComposeRun, which is functionally equivalent.

package proxy

import (
	"fmt"
	"os"
	"path/filepath"

	"digital.vasic.lava.tools.containers/internal/runtime"
)

// Orchestrator drives `compose --profile ... up/down` for the Lava root
// docker-compose.yml.
type Orchestrator struct {
	Runtime           *runtime.Runtime
	ProjectDir        string
	Profile           string // "api-go" | "legacy" | "both"
	WithObservability bool
	WithDevDocs       bool
}

// Profiles returns the resolved list of compose profiles based on the flag
// combination. The base profile is always included; observability and
// dev-docs are additive.
func (o *Orchestrator) Profiles() []string {
	profs := []string{o.Profile}
	if o.WithObservability {
		profs = append(profs, "observability")
	}
	if o.WithDevDocs {
		profs = append(profs, "dev-docs")
	}
	return profs
}

// ComposeArgs builds the "[--profile X]+" prefix for `compose up`/`compose
// down`. Each profile gets its own `--profile P` pair (compose accepts
// repeated --profile flags to union them).
func (o *Orchestrator) ComposeArgs() []string {
	var args []string
	for _, p := range o.Profiles() {
		args = append(args, "--profile", p)
	}
	return args
}

// ComposeUp runs `<runtime> compose [-f docker-compose.yml] --profile X
// [--profile Y]... up -d`.
func (o *Orchestrator) ComposeUp() error {
	composeFile := filepath.Join(o.ProjectDir, o.Runtime.ComposeFile())
	args := append([]string{"-f", composeFile}, o.ComposeArgs()...)
	args = append(args, "up", "-d")
	cmd := o.Runtime.ComposeRun(args...)
	cmd.Dir = o.ProjectDir
	cmd.Env = os.Environ()
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	fmt.Printf("[lava-containers] compose up: profiles=%v\n", o.Profiles())
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("compose up failed: %w", err)
	}
	return nil
}

// ComposeDown runs `<runtime> compose [-f docker-compose.yml] --profile X
// [--profile Y]... down`. Stopping respects the same profile set so we don't
// accidentally tear down sibling profiles the user didn't ask about.
func (o *Orchestrator) ComposeDown() error {
	composeFile := filepath.Join(o.ProjectDir, o.Runtime.ComposeFile())
	args := append([]string{"-f", composeFile}, o.ComposeArgs()...)
	args = append(args, "down")
	cmd := o.Runtime.ComposeRun(args...)
	cmd.Dir = o.ProjectDir
	cmd.Env = os.Environ()
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	fmt.Printf("[lava-containers] compose down: profiles=%v\n", o.Profiles())
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("compose down failed: %w", err)
	}
	return nil
}
