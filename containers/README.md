# digital.vasic.containers

Container management module for the Lava project.

## Purpose

This Go module provides a unified CLI for managing Lava's Docker/Podman containers. It handles:

- Runtime auto-detection (Podman preferred, Docker fallback)
- Proxy fat JAR building via Gradle
- Container image building
- Container lifecycle (start, stop, status, logs)
- LAN IP detection for mDNS advertisement

## Architecture

```
script (start.sh / stop.sh)
  └── containers/bin/lava-containers (Go binary)
        └── Docker / Podman container (lava-proxy)
```

## Building

```bash
cd containers
go build -o bin/lava-containers ./cmd/lava-containers
```

## Commands

| Command | Description |
|---------|-------------|
| `start` | Build JAR + image, start container, wait for health |
| `stop`  | Stop and remove the container |
| `status`| Show runtime, health, IPs |
| `logs`  | Stream container logs (`-f` to follow) |
| `build` | Build JAR and image without starting |

## Usage

```bash
# Start the proxy with LAN discoverability
./start.sh

# Check status
./containers/bin/lava-containers -cmd=status

# Stop
./stop.sh
```

## Network Discoverability

The proxy container uses **host networking** so that JmDNS mDNS broadcasts reach the local network. Android clients on the same Wi-Fi/LAN can auto-discover the proxy via `_lava._tcp` service discovery.

The host's LAN IP is detected automatically and passed to the container as `ADVERTISE_HOST`, ensuring the proxy advertises the correct reachable address.
