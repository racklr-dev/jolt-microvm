# Changelog

jolt-microvm is pinned by git SHA in downstream deps.edn files; tags are the
release markers. New work lands on `main` and is released by tagging.

## v1.7.0 — 2026-11-25

### Added

- Unikernel (bare-metal) example lane: boot a freestanding kernel under
  `libkrun/boot-external!` with no guest OS — a Linux-ABI C kernel
  (`linux-abi/`), a Rust `x86_64-unknown-none` kernel (`rust/`), and a Xen
  PVH probe (`pvh/`), plus `boot.clj` and `build.sh`; see
  `examples/unikernel/README.md`.

### Changed

- Desktop example README: tighten the VNC/xauth prerequisites and the
  `nix develop` wording.

## v1.6.0 — 2026-11-25

### Added

- `flake.nix`: nix builds for the desktop examples — `nix run .#desktop-x11`
  (or `.#desktop-wayland`, `.#desktop-x11-app`) runs the example on a nix-built
  jolt plus libkrun 2.0 built from source with the virtio-net backend and its
  musl-static `krun-init` guest init, joined with nixpkgs libkrunfw; `nix
  develop` provides the full dev toolchain (jolt, libkrun, ip/dnsmasq/nftables,
  ssh, xauth, a VNC client).
- `JOLT_LIBKRUN_INIT` env seam: `start!`/`run-image!` now take the guest-init
  path from the environment, so a nix-built `krun-init` can be injected (the
  runner previously hardcoded `/tmp/libkrun/target/release/krun-init`).

## v1.5.0 — 2026-11-25

### Added

- Alpine desktop examples: boot X11 or Wayland desktops in a microVM and serve
  them over VNC at the guest TAP IP (`examples/desktop/x11.clj`, `wayland.clj`).
- `jolt.microvm.desktop`: `profile` (apk package sets), `start-x11!`,
  `start-wayland!`, `stop-desktop!` (software rendering via Xvfb / wlroots
  headless+pixman, no GPU/display device needed).
- `vm/x11-exec`: run a single guest X11 app on the host display via `ssh -X`
  (`examples/desktop/x11-app.clj`).
- `start!` opts `:x11?` / `:tcp-forward?`: inject the matching sshd_config and
  make the sandbox home writable for xauth.
- `test/jolt/desktop_test.clj`: boots both profiles and asserts the VNC `RFB`
  banner.

### Fixed

- Raise the host runner's `RLIMIT_NOFILE` soft limit to 65536 (via `prlimit`,
  Linux only): libkrun's virtio-fs opens guest shared libraries on the host,
  and the Wayland desktop's library graph exceeded the common 1024-fd limit,
  breaking VNC.

## v1.4.1 — 2026-11-24

### Changed

- Bump jolt-oci to v0.3.3 (retry transient registry failures, status-check authed GETs).

## v1.4.0 — 2026-11-24

### Added

- OS-aware `check!`: reports the host OS and whether a hardware
  virtualization backend is available, and branches `:hypervisor`/`:kvm`
  instead of assuming one. CI runs the matrix on linux/macos/windows.
- Egress allow-list: `net-up!` gates DNS via dnsmasq and NATs only
  allow-listed domains/ports; the rest sinkhole. e2e test covers allowed
  resolves and sinkholed denies.
- Nix devShell layering: `print-dev-env` + `/nix/store` mount + exec env
  wrap, so a flake devShell is usable inside the sandbox.
- Mid-command interrupt: `exec`/`exec!` poll an interrupt atom and kill the
  ssh process while a command is running.

### Fixed

- Nix falls back to `/tmp` when `TMPDIR` points at a stale nix-shell dir.
- Egress teardown keys ride the VM record, so `stop!` clears nftables/NAT.

## v1.3.0 — 2026-11-23

### Added

- `start!` opt `:workspace-guest`: mount the workspace at a custom guest path
  (default `/workspace`). Set it to the host cwd's absolute path and guest `pwd`
  shows the host path, so relative paths read naturally. `exec`/`exec!` default
  their cwd to the workspace's guest path.
- `test/jolt/workspace_guest_test.clj`.

## v1.2.1 — 2026-11-23

### Fixed

- A mount whose guest path would clobber a boot-critical path (an ancestor of
  or equal to /home/sandbox/.ssh, /workspace, /run, /var/empty — e.g. mounting
  /home/sandbox itself) now throws a clear \"protected sandbox path\" error
  instead of booting a VM whose sshd auth is gone, which surfaced as every
  exec failing with ssh exit 255.
- `test/jolt/protected_mount_test.clj` covers the guard.

## v1.2.0 — 2026-11-23

### Added

- Read-only volume mounts. A mount entry may carry `:readonly?` / `:ro`
  (boolean) or `:mode` `"ro"`/`"rw"` (default `rw`); a read-only mount is
  mounted `-o ro` in the guest, so the sandbox can read host config but not
  write it. Also accepts `[host guest "ro"]` pairs.
- `test/jolt/readonly_mount_test.clj`: ro mount rejects writes, default mount
  accepts them.

## v1.1.0 — 2026-11-23

### Added

- `start!` opt `:writable-home?` (default `false`). When true, the warm VM's
  sandbox home (`/home/sandbox`) is left writable to its owner, so virtiofs
  mounts can be created **under** the home directory — the faraday case of
  mounting the host `~/.faraday` at the guest `~/.faraday` so config/auth inside
  the exec resolves naturally.
- `test/jolt/writable_home_test.clj`: integration coverage for both modes —
  mount-under-home boots and reads a file when the opt is on; `touch` in the
  home fails when it is off.

### Changed

- `inject-authorized-keys!` now takes a `:writable-home?` kwarg instead of
  always clearing the home's owner-write bit. Default behavior is unchanged
  (home read-only to the sandbox user), so existing callers are unaffected.

## v1.0.0 — 2026-11-21

- 2.0 libkrun builder/device API, per-VM TAP+dnsmasq networking, warm SSH
  sessions, snapshot/restore, and the direct-exec `run-image!` path.

## v0.5.0 — 2026-11-21

- Per-VM TAP+dnsmasq so concurrent boots multiplex cleanly.

## v0.4.0 — 2026-11-21

- Refcounted shared TAP+dnsmasq; egress e2e (DHCP + DNS + allow-listed NAT).

## v0.3.0 — 2026-11-21

- Volume mounts (virtio-fs) and the workspace mount.

## v0.2.0 — 2026-11-21

- First tagged release: libkrun boot + SSH exec.
