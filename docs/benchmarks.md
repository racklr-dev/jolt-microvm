# Benchmarks

Measured throughput for running commands inside jolt-microvm guests, on the
development host (18-core x86_64, writable `/dev/kvm`, libkrun 2.x). These are
single-host numbers, taken to size a warm pool; absolute numbers will differ
by CPU and libkrun version, but the two-path split below is the part that
matters.

There are two exec paths and they differ by an order of magnitude:

- **SSH exec** (`exec`/`exec!`, the default): one `ssh` round-trip per call.
  Simple and universal (any alpine guest), but SSH is the latency floor.
- **Daemon exec** (`start-daemon!` + `daemon-exec`/`daemon-run`): the
  direct-exec path, an in-guest `jolt-exec` daemon on a TCP port, no SSH.
  Fast, but the guest must be glibc (distroless debian13 / debian-slim, not
  alpine/musl) and the daemon binary is baked into the rootfs.

## SSH exec

| scenario | measured |
| --- | --- |
| cold boot (`run-image!`) | ~2.1 s |
| warm `exec` (raw ssh) | ~242 ms/call |
| warm `exec` (ssh `ControlMaster=auto ControlPersist=120`) | ~60 ms/call |
| 4 warm VMs × 40 execs, raw ssh | ~16 exec/s |
| 4 warm VMs × 40 execs, ControlMaster | ~56 exec/s |

Rule of thumb: SSH exec tops out near ~16 exec/s per VM with connection
multiplexing on this host. Reuse the SSH control socket (`ControlMaster=auto`,
`ControlPath=/tmp/jolt-ssh-ctl-PORT`, `ControlPersist=120`) or you pay ~4×.

## Daemon exec

| scenario | measured |
| --- | --- |
| single `daemon-exec` call | ~17 ms |
| pool-wide, 10 warm VMs, host concurrency 96 to 128 | ~1000 to 1134 exec/s (~1 ms avg) |
| per-VM, 10 warm VMs (contended) | ~72 to 113 exec/s |
| per-VM, 2 warm VMs (less contention) | ~185 exec/s |
| sweep ceiling | knee at ~128 concurrent calls; 384 saturates |

So the short "how many requests per second" answer:

- **~56/s over 4 VMs** on the SSH path (what `exec` gives you);
- **~1000/s over 10 VMs** (or roughly **400 to 700/s over 4 VMs**) on the
  daemon path, where the per-VM rate depends on how many VMs contend for host cores.

Daemon guests must be `memory-mib 1024`: the jolt runner caps the guest heap
at 25% of detected guest RAM, and the exec-daemon's ~88 MB live heap exceeds
the 64 MB ceiling a 256 MB guest gets.

## Reproducing

The daemon path is reproducible from this repo via
[`examples/bench/daemon_probe.clj`](../examples/bench/daemon_probe.clj). It
boots K warm in-guest exec daemons once, then sweeps N concurrent calls over
the same VMs (each wave is written to `/tmp/jolt-bench-results.edn`):

```sh
# 1. compile the exec-daemon binary (drop the compiler image -> ~12 MB)
jolt build -m jolt.exec.daemon -o /tmp/exec-daemon

# 2. run the sweep (needs a writable /dev/kvm)
MP_N=2000 MP_K=10 MP_CONCS=16,64,128,256 \
  MP_DAEMON_BIN=/tmp/exec-daemon \
  jolt run examples/bench/daemon_probe.clj
```

The SSH-multiplex scale proof lives in faraday
(`faraday/rap/test/faraday/multiplex_probe.clj`), because it exercises
faraday's `rap`/`pool` orchestration, not jolt-microvm alone.
