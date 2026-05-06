// Lava-domain CLI: orchestrates the lava-api-go service via docker-compose
// profiles.
//
// 2026-05-06: the legacy Ktor proxy was removed from the codebase.
// This CLI now drives the api-go profile only (with optional observability
// + dev-docs profiles).
//
// This is intentionally Lava-specific (knows about gradlew, the
// digital.vasic.lava.api image, the api-go|observability|dev-docs profile
// names from docker-compose.yml). Generic container-runtime concerns are
// owned by vasic-digital/Containers (mounted at /Submodules/Containers/).
//
// Until Submodules/Containers/pkg/compose grows a multi-profile API
// (today its ComposeProject.Profile is a single string), this CLI shells
// out directly to `<runtime> compose --profile X --profile Y up -d`
// rather than going through the upstream orchestrator.
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
		profile           = flag.String("profile", "api-go", "Compose profile: api-go (the only supported profile post-2026-05-06)")
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
		if err := mgr.BuildImage(); err != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}
	default:
		fmt.Fprintf(os.Stderr, "Unknown command: %s\n", *cmd)
		fmt.Fprintf(os.Stderr,
			"Usage: %s [-cmd=start|stop|status|logs|build] [-profile=api-go] "+
				"[-with-observability] [-dev-docs] [-project-dir=PATH]\n",
			os.Args[0])
		os.Exit(1)
	}
}

// validateProfile rejects unknown --profile values. Post-2026-05-06 the
// only supported profile is api-go (the Ktor proxy was removed); the
// flag is retained so docker-compose --profile invocations stay
// explicit and the orchestrator's profile-aware compose-up call site
// doesn't need a special case.
func validateProfile(p string) error {
	switch p {
	case "api-go":
		return nil
	default:
		return fmt.Errorf("invalid --profile=%q (want api-go)", p)
	}
}

// runStart handles the "start" command. Delegates to the Orchestrator
// which runs `compose --profile api-go up -d` (plus optional
// observability + dev-docs profiles). The legacy Ktor JAR-build path
// was removed in 2026-05-06 with the :proxy module deletion.
func runStart(_ *proxy.Manager, orch *proxy.Orchestrator) error {
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
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
		dir = parent
	}
	wd, _ := os.Getwd()
	return wd
}
