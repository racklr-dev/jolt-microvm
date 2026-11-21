# jolt-microvm

Run commands inside an OCI-image-backed [libkrun](https://github.com/containers/libkrun)
microVM over SSH — a port of dirge's `sandbox/microvm` (runner.rs, rootfs.rs, ssh.rs)
to [jolt](https://github.com/jolt-lang/jolt).

```clojure
(require '[jolt.microvm :as vm]
         '[jolt.microvm.rootfs :as rootfs])

;; one-time: seed the cached base with sshd (no buildah/docker needed)
(rootfs/install-alpine-packages! (rootfs/base! "alpine:3.20" "/tmp/cache") "openssh")

(def v (vm/start! {:image "alpine:3.20"
                   :cache-dir "/tmp/cache"
                   :workspace "/tmp/ws"
                   :project-dir "/path/to/your/project"}))

(vm/exec v "uname -a")            ; => {:stdout "Linux ..." :stderr "" :exit 0}
(vm/exec v "echo hi > /workspace/x.txt")
(slurp "/tmp/ws/x.txt")           ; => "hi\n"

(vm/save-snapshot! v "pre-upgrade")
(vm/reboot! v)                      ; stop + fresh start; in-VM changes discarded
(vm/stop! v)
(vm/restore-snapshot! v "pre-upgrade")  ; replaces cached base
(vm/delete-snapshot! v "pre-upgrade")
```

## How it works

- `jolt.oci` pulls the image and unpacks it into a cached base rootfs, cloned
  per session (symlinks, hardlinks, modes, whiteouts all honoured).
- A `sandbox` user, ephemeral ed25519 client key, host keys, and
  `/.krun_config.json` (sshd init) are injected into the rootfs.
- `boot!` runs in a **subprocess** (like dirge's runner binary) so `stop!` can
  kill it: `jolt run -m jolt.microvm.runner <json>`.
- Commands run over SSH with the guest host key pinned (MITM-safe on the
  localhost port forward); a virtio-fs device mounts the host `:workspace` at
  `/workspace` in the guest.

## Requirements

- libkrun + libkrunfw (`libkrun.so` / `libkrun.dylib` on `:jolt/native`).
- Linux: `/dev/kvm` — your user must be in the `kvm` group. **Important:** the
  process must keep its normal primary gid; libkrun's virtio-fs only permits
  operations whose uid/gid match the daemon's own (or root). `sg kvm` (which
  *replaces* the primary gid) breaks guest writes — use a login shell where
  `kvm` is a supplementary group, or `setpriv --regid $(id -g) --groups kvm,...`.
- `ssh-keygen` and an `ssh` client on the host.
- `sudo` for `install-alpine-packages!` (runs the image's own `apk` with its
  musl loader).

## Modules

- `jolt.microvm` — `start!` / `exec` / `stop!` / `save-snapshot!` /
  `list-snapshots` / `restore-snapshot!` / `delete-snapshot!` / `reboot!`,
  plus macOS `dyld-fallback-path` and `ensure-hypervisor-entitlement!`.
- `jolt.microvm.rootfs` — `base!`, `prepare!`, `install-alpine-packages!`
  (chowns the apk-installed base back to the user), `cp-r!`, `cleanup!`,
  user/key/krun-config injection.
- `jolt.microvm.keys` — ephemeral + host ed25519 keys via `ssh-keygen`.
- `jolt.microvm.runner` — the subprocess `-main` that caps `RLIMIT_NOFILE`
  (getrlimit/setrlimit) and blocks in `boot!` with `krun_set_rlimits`.

Sibling: [jolt-pty](https://github.com/racklr-dev/jolt-pty) has the reusable
POSIX pseudo-terminal primitives (the PTY core of dirge's pty_harness.rs).

## License

EPL-2.0
