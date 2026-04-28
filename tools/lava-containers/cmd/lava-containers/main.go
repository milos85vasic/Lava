// Lava-domain CLI: orchestrates the legacy Ktor proxy and/or the new Go API
// service via docker-compose profiles.
//
// This is intentionally Lava-specific (knows about gradlew, the proxy module,
// the digital.vasic.lava.api image, the api-go|legacy|both|observability|
// dev-docs profile names from docker-compose.yml). Generic container-runtime
// concerns are owned by vasic-digital/Containers (mounted at
// /Submodules/Containers/).
//
// SP-2 Phase 11 / Task 11.3 added the compose-profile flag plumbing
// (--profile, --with-observability, --dev-docs). Until
// Submodules/Containers/pkg/compose grows a multi-profile API
// (today its ComposeProject.Profile is a single string), this CLI shells out
// directly to `<runtime> compose --profile X --profile Y up -d` rather than
// going through the upstream orchestrator. Once the upstream exposes a
// profile-aware primitive, the call sites in
// internal/proxy.OrchestratorComposeUp / OrchestratorComposeDown should be
// rewritten to delegate.
package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"

	"digital.vasic.lava.tools.containers/internal/proxy"
)

func main() {
	var (
		cmd               = flag.String("cmd", "status", "Command: start, stop, status, logs, build")
		projectDir        = flag.String("project-dir", autoDetectProjectDir(), "Path to the Lava project root")
		followLogs        = flag.Bool("f", false, "Follow logs (for logs command)")
		profile           = flag.String("profile", "api-go", "Compose profile: api-go (default), legacy, or both")
		withObservability = flag.Bool("with-observability", false, "Include the observability profile (Prometheus/Loki/Promtail/Tempo/Grafana)")
		devDocs           = flag.Bool("dev-docs", false, "Include the dev-docs profile (Swagger UI)")
	)
	flag.Parse()

	if err := validateProfile(*profile); err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}

	mgr, err := proxy.NewManager(*projectDir)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}

	orch := &proxy.Orchestrator{
		Runtime:           mgr.Runtime,
		ProjectDir:        mgr.ProjectDir,
		Profile:           *profile,
		WithObservability: *withObservability,
		WithDevDocs:       *devDocs,
	}

	switch *cmd {
	case "start":
		if err := runStart(mgr, orch); err != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}
	case "stop":
		if err := orch.ComposeDown(); err != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}
	case "status":
		if err := mgr.Status(); err != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}
	case "logs":
		if err := mgr.Logs(*followLogs); err != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}
	case "build":
		if err := mgr.BuildJar(); err != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}
		if err := mgr.BuildImage(); err != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}
	default:
		fmt.Fprintf(os.Stderr, "Unknown command: %s\n", *cmd)
		fmt.Fprintf(os.Stderr,
			"Usage: %s [-cmd=start|stop|status|logs|build] [-profile=api-go|legacy|both] "+
				"[-with-observability] [-dev-docs] [-project-dir=PATH]\n",
			os.Args[0])
		os.Exit(1)
	}
}

// validateProfile rejects unknown --profile values.
func validateProfile(p string) error {
	switch p {
	case "api-go", "legacy", "both":
		return nil
	default:
		return fmt.Errorf("invalid --profile=%q (want api-go, legacy, or both)", p)
	}
}

// runStart handles the "start" command. For the legacy profile we keep the
// existing build-and-up pipeline (Gradle JAR + image + compose up) so
// behaviour is unchanged. For api-go and both, we delegate to the
// Orchestrator which runs `compose --profile X up -d`.
func runStart(mgr *proxy.Manager, orch *proxy.Orchestrator) error {
	if orch.Profile == "legacy" {
		jarPath := filepath.Join(mgr.ProjectDir, "proxy", "build", "libs", "app.jar")
		if _, err := os.Stat(jarPath); os.IsNotExist(err) {
			if err := mgr.BuildJar(); err != nil {
				return err
			}
		}
		if err := mgr.BuildImage(); err != nil {
			return err
		}
	}
	return orch.ComposeUp()
}

func autoDetectProjectDir() string {
	exe, err := os.Executable()
	if err != nil {
		wd, _ := os.Getwd()
		return wd
	}
	dir := filepath.Dir(exe)
	for {
		if _, err := os.Stat(filepath.Join(dir, "gradlew")); err == nil {
			return dir
		}
		if _, err := os.Stat(filepath.Join(dir, "proxy")); err == nil {
			return dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
		dir = parent
	}
	wd, _ := os.Getwd()
	return wd
}
