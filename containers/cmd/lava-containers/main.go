package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"

	"digital.vasic.containers/internal/proxy"
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
	// Build JAR if missing
	jarPath := filepath.Join(mgr.ProjectDir, "proxy", "build", "libs", "app.jar")
	if _, err := os.Stat(jarPath); os.IsNotExist(err) {
		if err := mgr.BuildJar(); err != nil {
			return err
		}
	}
	// Build image
	if err := mgr.BuildImage(); err != nil {
		return err
	}
	// Start container
	return mgr.Start()
}

func autoDetectProjectDir() string {
	// If running from containers/ directory, go up one level.
	exe, err := os.Executable()
	if err != nil {
		wd, _ := os.Getwd()
		return wd
	}
	dir := filepath.Dir(exe)
	// Walk up until we find gradlew or proxy/ directory
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
