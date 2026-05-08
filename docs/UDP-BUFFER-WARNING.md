# quic-go UDP Receive Buffer Warning

## Symptom

At boot, the `lava-api-go` container logs:

```
failed to sufficiently increase receive buffer size
(was: 208 kiB, wanted: 7168 kiB, got: 416 kiB).
```

This is a quic-go warning indicating the kernel's UDP receive buffer is
too small for the desired HTTP/3 performance. The container tries to set
`net.core.rmem_max` via `setsockopt(SO_RCVBUF)` but the kernel caps it at
the system-wide maximum (`net.core.rmem_max`).

## Fix

On the host machine, increase the max UDP receive buffer:

```bash
sudo sysctl -w net.core.rmem_max=7340032
```

Make it permanent (survive reboot):

```bash
echo "net.core.rmem_max=7340032" | sudo tee /etc/sysctl.d/99-lava-udp-buffer.conf
```

The value `7340032` is `7168 KiB` (the buffer size quic-go requests).

## Verification

After applying, restart the api-go container:

```bash
./stop.sh && ./start.sh
```

Then check the container logs — the warning should be gone. If it
persists, verify the sysctl took effect:

```bash
sysctl net.core.rmem_max
# Expected: net.core.rmem_max = 7340032
```

## Reference

- quic-go default buffer target: 7168 KiB (7340032 bytes)
- Linux default `net.core.rmem_max`: 208 KiB (212992 bytes) — too small
- Linux kernel caps `setsockopt(SO_RCVBUF)` at `net.core.rmem_max`
