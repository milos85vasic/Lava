// Lava-domain CLI: orchestrates the legacy Ktor proxy container's lifecycle.
//
// This is intentionally Lava-specific (knows about gradlew, the proxy module,
// the digital.vasic.lava.api image). Generic container-runtime concerns are
// handled by vasic-digital/Containers (mounted at /Submodules/Containers/);
// SP-2 will rewire this CLI to delegate runtime detection / IP scanning /
// lifecycle to upstream APIs.
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
		cmd        = flag.String("cmd", "status", "Command: start, stop, status, logs, build")
		projectDir = flag.String("project-dir", autoDetectProjectDir(), "Path to the Lava project root")
		followLogs = flag.Bool("f", false, "Follow logs (for logs command)")
	)
	flag.Parse()

	mgr, err := proxy.NewManager(*projectDir)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}

	switch *cmd {
	case "start":
		if err := runStart(mgr); err != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}
	case "stop":
		if err := mgr.Stop(); err != nil {
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
		fmt.Fprintf(os.Stderr, "Usage: %s [-cmd=start|stop|status|logs|build] [-project-dir=PATH]\n", os.Args[0])
		os.Exit(1)
	}
}

func runStart(mgr *proxy.Manager) error {
	jarPath := filepath.Join(mgr.ProjectDir, "proxy", "build", "libs", "app.jar")
	if _, err := os.Stat(jarPath); os.IsNotExist(err) {
		if err := mgr.BuildJar(); err != nil {
			return err
		}
	}
	if err := mgr.BuildImage(); err != nil {
		return err
	}
	return mgr.Start()
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
