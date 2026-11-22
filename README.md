# jolt-microvm

Run commands inside an OCI-image-backed [libkrun](https://github.com/containers/libkrun)
microVM over SSH — a port of dirge's `sandbox/microvm` (runner.rs, rootfs.rs, ssh.rs)
to [jolt](https://github.com/jolt-lang/jolt).

## Plug-and-play

```clojure
;; deps.edn
{:deps {racklr-dev/jolt-microvm
        {:git/url "https://github.com/racklr-dev/jolt-microvm"
         :git/sha "<latest sha>"}}}
```

```clojure
(require '[jolt.microvm :as vm])

;; start! auto-seeds sshd into the cached alpine base on first use,
;; runs a pre-flight check, and throws a readable error if something is missing.
(def v (vm/start! {:image "alpine:3.20"
                   :cache-dir "/tmp/cache"
                   :workspace "/tmp/ws"
                   :project-dir "/path/to/your/project"}))

(vm/exec v "uname -a")            ; => {:stdout "Linux ..." :stderr "" :exit 0}
(vm/exec v "echo hi > /workspace/x.txt")
(slurp "/tmp/ws/x.txt")           ; => "hi\n"

;; exec has a wall-clock timeout (default 300000ms); a hung guest can't wedge you
(vm/exec v "sleep 60" :timeout-ms 3000)   ; throws ex-info "ssh command timed out"

(vm/save-snapshot! v "pre-upgrade")
(vm/reboot! v)                      ; stop + fresh start; in-VM changes discarded
(vm/stop! v)
(vm/restore-snapshot! v "pre-upgrade")  ; replaces cached base
(vm/delete-snapshot! v "pre-upgrade")
```

### Pre-flight check

```clojure
(vm/check!)
;; {:ok? true
;;  :results [{:name :libkrun :status :ok :message "/usr/local/lib/libkrun.so" :fix nil}
;;            {:name :libkrunfw :status :ok ...}
;;            {:name :kvm :status :ok ...}
;;            {:name :ssh :status :ok ...}
;;            {:name :ssh-keygen :status :ok ...}
;;            {:name :sudo :status :ok ...}
;;            {:name :jolt-bin :status :ok ...}]}
```

`start!` runs `check!` and throws on `:error` results with the fix hint in the
message (missing libkrun, `/dev/kvm` not writable, no ssh client, etc.).

## How it works

- `jolt.oci` pulls the image and unpacks it into a cached base rootfs, cloned
  per session (symlinks, hardlinks, modes, whiteouts all honoured).
- First use auto-seeds `openssh` into the cached alpine base with the image's
  own `apk` + musl loader (file-locked so concurrent `start!`s don't race); no
  buildah/docker needed.
- A `sandbox` user, ephemeral ed25519 client key, host keys, and
  `/.krun_config.json` (sshd init) are injected into the rootfs.
- `boot!` runs in a **subprocess** (like dirge's runner binary) so `stop!` can
  kill it: `jolt run -m jolt.microvm.runner <json>`.
- Commands run over SSH with the guest host key pinned (MITM-safe on the
  localhost port forward); a virtio-fs device mounts the host `:workspace` at
  `/workspace` in the guest.

## Requirements

- libkrun + libkrunfw (`libkrun.so` / `libkrun.dylib`).
- Linux: `/dev/kvm` — your user must be in the `kvm` group. **Important:** the
  process must keep its normal primary gid; libkrun's virtio-fs only permits
  operations whose uid/gid match the daemon's own (or root). `sg kvm` (which
  *replaces* the primary gid) breaks guest writes — use a login shell where
  `kvm` is a supplementary group, or `setpriv --regid $(id -g) --groups kvm,...`.
- `ssh-keygen` and an `ssh` client on the host.
- `sudo` (only for the automatic alpine sshd seed).
- The jolt binary at `~/.local/bin/jolt` (or set `JOLT_BIN`) — the runner is
  spawned as a jolt subprocess.

## Modules

- `jolt.microvm` — `check!`, `start!` / `exec` / `stop!` / `save-snapshot!` /
  `list-snapshots` / `restore-snapshot!` / `delete-snapshot!` / `reboot!`,
  plus macOS `dyld-fallback-path` and `ensure-hypervisor-entitlement!`.
- `jolt.microvm.rootfs` — `base!`, `prepare!`, `musl-loader`,
  `install-alpine-packages!` (chowns the apk-installed base back to the user),
  `cp-r!`, `cleanup!`, user/key/krun-config injection.
- `jolt.microvm.keys` — ephemeral + host ed25519 keys via `ssh-keygen`.
- `jolt.microvm.runner` — the subprocess `-main` that caps `RLIMIT_NOFILE`
  (getrlimit/setrlimit) and blocks in `boot!` with `krun_set_rlimits`.

Siblings: [jolt-pty](https://github.com/racklr-dev/jolt-pty) (POSIX pty
primitives), [jolt-libkrun](https://github.com/racklr-dev/jolt-libkrun)
(krun_* bindings + lib probe), [jolt-oci](https://github.com/racklr-dev/jolt-oci)
(OCI pull + unpack).

## License

EPL-2.0
