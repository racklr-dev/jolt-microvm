# Changelog

jolt-microvm is pinned by git SHA in downstream deps.edn files; tags are the
release markers. New work lands on `main` and is released by tagging.

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
