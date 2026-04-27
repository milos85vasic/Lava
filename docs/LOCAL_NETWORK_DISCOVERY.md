# Local Network Discovery

## Overview

Lava supports automatic discovery of proxy servers running on your local network. When you open the Settings menu, the app scans for nearby Lava proxy instances and offers to connect to them automatically.

## How It Works

### mDNS Service Discovery

The Lava proxy advertises itself on the local network using **mDNS** (multicast DNS) with the service type `_lava._tcp`. The Android app uses the platform's `NsdManager` to discover these services.

### Discovery Flow

1. **Proxy Advertises**: When the proxy server starts, it registers an mDNS service announcing its IP address and port.
2. **App Scans**: When you open the Settings menu, the app starts a 5-second network scan.
3. **Auto-Connect**: If a proxy is found and you are not already using a custom mirror, the app automatically adds it to your endpoints and opens the connection settings dialog.
4. **Manual Selection**: You can always switch endpoints manually in the connection settings.

## Running a Local Proxy

### Using Docker Compose

```bash
./start.sh
```

This script:
1. Builds the proxy fat JAR
2. Builds a Docker image
3. Starts the container with port `8080` exposed
4. Waits for the service to be healthy

### Manual Start

```bash
./gradlew :proxy:buildFatJar
java -jar proxy/build/libs/app.jar
```

The proxy listens on `0.0.0.0:8080` and advertises itself via mDNS.

## Requirements

- Both devices must be on the **same local network** (same Wi-Fi or LAN).
- Android 5.0+ (API 21+) for the app.
- The proxy host must allow multicast traffic (most home routers do by default).

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Proxy not discovered | Ensure both devices are on the same network. Check firewall rules for mDNS (port 5353/UDP). |
| Discovery is slow | mDNS can take a few seconds. The scan timeout is 5 seconds. |
| Auto-connect didn't happen | If you already have a custom mirror configured, the app will not auto-switch. Manually select the discovered endpoint in connection settings. |
| mDNS blocked on corporate networks | Some corporate networks block multicast. Use manual endpoint entry instead. |
