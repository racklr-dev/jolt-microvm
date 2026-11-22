# jolt-microvm

[![tests](https://github.com/racklr-dev/jolt-microvm/actions/workflows/ci.yml/badge.svg)](https://github.com/racklr-dev/jolt-microvm/actions/workflows/ci.yml)

Run commands inside an OCI-image-backed [libkrun](https://github.com/containers/libkrun)
microVM over SSH — a port of dirge's `sandbox/microvm` to
[jolt](https://github.com/jolt-lang/jolt). Boot any alpine-based image (any
language runtime, via `:packages`), mount host directories, run commands or a
long-lived agent session over SSH, and snapshot the rootfs.

## Plug-and-play

```clojure
{:deps {racklr-dev/jolt-microvm
        {:git/url "https://github.com/racklr-dev/jolt-microvm"
         :git/sha "<latest sha>"}}}
```

```clojure
(require '[jolt.microvm :as vm])

;; start! auto-seeds openssh + any :packages into the cached alpine base,
;; runs a pre-flight check, and throws a readable error if something is missing.
(vm/with-vm [v {:image "alpine:3.20"
                :packages ["python3"]
                :mounts {"/host/data" "/data"}
                :workspace "/tmp/ws"
                :cache-dir "/tmp/cache"
                :project-dir "/path/to/your/project"}]
  (vm/exec v "python3 -c 'print(42)'")     ; => {:stdout "42\n" :stderr "" :exit 0}
  (vm/exec v "echo hi > /workspace/x.txt") ; workspace mount, host-readable
  (vm/exec v "echo data > /data/y.txt")    ; extra mount
  (slurp "/tmp/ws/x.txt"))                 ; => "hi\n"
```

`with-vm` boots, runs the body, and `stop!`s in a `finally`. Or manage the
lifecycle by hand:

```clojure
(def v (vm/start! {:image "alpine:3.20"
                   :cache-dir "/tmp/cache"
                   :project-dir "/path/to/your/project"}))

(vm/exec v "uname -a")          ; {:stdout :stderr :exit}
(vm/exec! v "true")             ; like exec, throws on nonzero exit
(vm/exec v "sleep 60" :timeout-ms 3000)  ; throws on wall-clock timeout

(vm/copy-to! v "/host/file" "/workspace/file")   ; host -> guest (scp)
(vm/copy-from! v "/workspace/file" "/host/file") ; guest -> host (scp)

(vm/alive? v)  ; => true
(vm/info v)    ; {:image :port :rootfs :workspace :mounts :running?}

(vm/save-snapshot! v "pre-upgrade")
(vm/reboot! v)                   ; stop + fresh start; in-VM changes discarded
(vm/stop! v)
(vm/restore-snapshot! v "pre-upgrade")
(vm/delete-snapshot! v "pre-upgrade")
```

### Long-running agent sessions

A VM stays booted until `stop!`, so an agent can keep one warm and `exec` many
commands into the same rootfs:

```clojure
(vm/with-vm [v {:image "alpine:3.20"
                :packages ["python3" "nodejs"]
                :mounts {"/srv/repo" "/repo"}
                :project-dir "/path/to/your/project"}]
  (vm/exec! v "cd /repo && python3 -m pytest")   ; run your test suite sandboxed
  (vm/exec v "node -e 'console.log(1+1)'"))
```

### Interactive shell

```clojure
(vm/attach! v)   ; SSH session over an isolated pty; host terminal size applied
```

## CLI

```bash
jolt run -m jolt.microvm.cli check
jolt run -m jolt.microvm.cli exec alpine:3.20 uname -a
jolt run -m jolt.microvm.cli run  alpine:3.20 python3 -c 'print(42)'
jolt run -m jolt.microvm.cli shell alpine:3.20
jolt run -m jolt.microvm.cli install alpine:3.20 python3 nodejs
jolt run -m jolt.microvm.cli snapshots alpine:3.20
```

`exec` streams output and exits with the guest command's status; `run` throws
on nonzero; `shell` attaches interactively. The CLI uses the project dir it is
launched from for dependency resolution (`user.dir`).

## Volume mounts

`:workspace` is the host dir mounted at `/workspace` (default
`rootfs/workspace`). `:mounts` adds more, in any of three shapes:

```clojure
{:mounts {"/host/a" "/a" "/host/b" "/b"}}   ; host->guest map
{:mounts [{:host "/host/a" :guest "/a" :tag "a"}]}  ; specs (tag optional)
{:mounts [["/host/a" "/a"] ["/host/b" "/b"]]}      ; pairs
```

Each mount is a separate virtio-fs device; the guest sees them at their guest
paths. Writes are visible on the host immediately.

## Multi-arch

`start!` resolves the OCI image for the host architecture by default
(x86_64 -> amd64, aarch64 -> arm64). Override with `:arch`:

```clojure
(vm/start! {:image "alpine:3.20" :arch "arm64" ...})
```

The per-arch cache is isolated, and `jolt-oci` supports digest refs
(`alpine@sha256:...`) too.

## Installing packages into the base

`:packages` seeds the cached base before boot (alpine `apk`, no docker needed).
To add packages without booting, or for the next start:

```clojure
(vm/install! v "python3")             ; VM-aware
(vm/install-image! "alpine:3.20" ["nodejs"] :cache-dir "/tmp/cache")
```

## Pre-flight check

```clojure
(vm/check!)
;; {:ok? true :results [{:name :libkrun :status :ok :message "/usr/local/lib/libkrun.so" :fix nil} ...]}
```

`start!` runs `check!` and throws on `:error` results with a fix hint in the
message. `:scp` and `:sudo` are warnings (copy helpers / auto-seed unavailable).

## Requirements

- libkrun + libkrunfw (`libkrun.so` / `libkrun.dylib`).
- Linux: `/dev/kvm` writable (user in the `kvm` group). **Keep your normal
  primary gid** — `sg kvm` breaks virtio-fs guest writes; use a login shell or
  `setpriv --regid $(id -g) --groups kvm,...`.
- `ssh`, `ssh-keygen` (and `scp` for copy helpers) on the host.
- `sudo` only for the automatic alpine seed.
- The jolt binary at `~/.local/bin/jolt` (or set `JOLT_BIN`) — the runner is a
  jolt subprocess.

## Modules

- `jolt.microvm` — `check!`, `start!`/`exec`/`exec!`/`attach!`/`stop!`,
  `with-vm`, `alive?`, `info`, `copy-to!`/`copy-from!`,
  `install!`/`install-image!`, `save-snapshot!`/`list-snapshots`/
  `restore-snapshot!`/`delete-snapshot!`/`reboot!`, macOS helpers.
- `jolt.microvm.cli` — the `jolt run -m` command-line interface.
- `jolt.microvm.rootfs` — `base!`, `prepare!`, `musl-loader`,
  `alpine-installed?`, `install-alpine-packages!`, `cp-r!`, `cleanup!`,
  user/key/krun-config injection, multi-mount config.
- `jolt.microvm.keys` — ephemeral + host ed25519 keys via `ssh-keygen`.
- `jolt.microvm.runner` — subprocess `-main` that caps `RLIMIT_NOFILE` and
  blocks in `boot!` with all virtio-fs mounts.

Siblings: [jolt-pty](https://github.com/racklr-dev/jolt-pty) (pty primitives +
console relay), [jolt-libkrun](https://github.com/racklr-dev/jolt-libkrun)
(krun_* bindings + lib probe), [jolt-oci](https://github.com/racklr-dev/jolt-oci)
(multi-arch OCI pull + unpack).

## Direct-exec (distroless and shell-less images)

`start!` is the SSH *session* mode — it seeds openssh, so it needs an alpine
(apk) base or an image that already ships sshd. For distroless and other
images with no shell/sshd, use `run-image!`, which boots the image straight
into a command via `krun_set_exec` (no SSH) and returns the output:

```clojure
(vm/run-image! {:image "gcr.io/distroless/python3-debian13:latest"
                :cmd ["/usr/bin/python3" "-c" "print('hi')"]
                :cache-dir "/tmp/cache"
                :project-dir "/path/to/your/project"})
;; => {:stdout "hi\n" :stderr "" :exit 0}
```

`run-image!` is one-shot and blocking (with `:timeout-ms`); the guest's exit
code is the returned `:exit`. It works for any image whose rootfs contains the
executable — alpine with `/bin/sh`, distroless language images, or your own
layered binary.

## License

EPL-2.0
