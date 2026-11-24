# jolt-microvm — OCI-image-backed libkrun microVMs over SSH

Run commands inside an OCI-image-backed [libkrun](https://github.com/containers/libkrun)
microVM over SSH, from [jolt](https://github.com/jolt-lang/jolt). Boot any
alpine-based image (any language runtime, via `:packages`), mount host
directories, run commands or a long-lived agent session over SSH, and snapshot
the rootfs. A direct-exec mode boots distroless and shell-less images with no
SSH.

## Requirements

- libkrun + libkrunfw (`libkrun.so` / `libkrun.dylib`)
- Linux: a writable `/dev/kvm` (user in the `kvm` group) — keep your normal
  primary gid: `sg kvm` breaks virtio-fs guest writes
- `ssh`, `ssh-keygen` (and `scp` for the copy helpers) on the host
- `sudo` only for the automatic alpine seed
- [jolt](https://github.com/jolt-lang/jolt) 0.8.28+ (the runner is a jolt
  subprocess)

## Build

Nothing to compile. Run the suite:

```sh
jolt run test/jolt/check_test.clj    # pre-flight + smoke
jolt run test/jolt/snapshot_test.clj
jolt run test/jolt/exec_mode_test.clj
jolt run test/jolt/egress_test.clj
jolt run test/jolt/attach_test.clj
```

The boot tests need the requirements above; `check!` reports each one with a
fix hint.

## Usage

```clojure
{:deps {racklr-dev/jolt-microvm
        {:git/url "https://github.com/racklr-dev/jolt-microvm"
         :git/sha "<pinned sha>"}}}
```

```clojure
(require '[jolt.microvm :as vm])

;; with-vm boots, runs the body, and stop!s in a finally
(vm/with-vm [v {:image "alpine:3.20"
                :packages ["python3"]
                :mounts {"/host/data" "/data"}
                :workspace "/tmp/ws"
                :cache-dir "/tmp/cache"
                :project-dir "/path/to/your/project"}]
  (vm/exec v "python3 -c 'print(42)'")     ; => {:stdout "42\n" :stderr "" :exit 0}
  (vm/exec v "echo hi > /workspace/x.txt")
  (slurp "/tmp/ws/x.txt"))                 ; => "hi\n"
```

Manage the lifecycle by hand:

```clojure
(def v (vm/start! {:image "alpine:3.20"
                   :cache-dir "/tmp/cache"
                   :project-dir "/path/to/your/project"}))

(vm/exec v "uname -a")          ; => {:stdout ... :stderr ... :exit ...}
(vm/exec! v "true")             ; like exec, throws on nonzero exit
(vm/exec v "sleep 60" :timeout-ms 3000)   ; throws on wall-clock timeout
(vm/copy-to! v "/host/file" "/workspace/file")   ; host -> guest (scp)
(vm/copy-from! v "/workspace/file" "/host/file") ; guest -> host (scp)
(vm/alive? v)                   ; => true
(vm/info v)                     ; {:image :port :rootfs :workspace :mounts :running?}

(vm/save-snapshot! v "pre-upgrade")
(vm/reboot! v)                  ; stop + fresh start; in-VM changes discarded
(vm/stop! v)
(vm/restore-snapshot! v "pre-upgrade")
(vm/delete-snapshot! v "pre-upgrade")
```

`start!` auto-seeds openssh + any `:packages` into the cached alpine base, runs
a pre-flight check, and throws a readable error if something is missing.
`:mounts` accepts a host→guest map, a vector of `{:host :guest :tag}` specs, or
a vector of `[host guest]` pairs; each mount is a separate virtio-fs device.
A mount may carry `:readonly?` / `:ro` (boolean) or `:mode` `"ro"`/`"rw"`
(default `rw`): a read-only mount is mounted `-o ro` in the guest, so the
sandbox can read config but not write it.
`:writable-home?` (default false) leaves the sandbox home writable so mounts can
be created under `/home/sandbox`: e.g. mount the host `~/.config` there and a
guest tool reads the same settings. When off, the home stays
non-writable to the sandbox user (sshd hygiene).

`:nix-flake` layers a nix flake devShell into the VM: `start!` resolves it with
`nix print-dev-env` on the host, mounts the host `/nix/store` read-only at
`/nix/store`, and wraps every `exec` in the devShell env (PATH + the flake's
env vars) via the nix bash. Relative refs like `.#default` resolve against the
`:workspace` dir (the project). Needs nix + flakes on the host; the first boot
fetches/builds the devShell closure into `/nix/store`, then it is cached.

```clojure
(vm/with-vm [v {:image "alpine:3.20"
                :workspace "/path/to/your/project"   ; the dir with flake.nix
                :nix-flake ".#default"}]
  (vm/exec v "gcc --version")          ; the flake's stdenv toolchain
  (vm/exec v "hello"))                 ; a package from the devShell
```
`:arch` overrides the host-architecture default (x86_64→amd64, aarch64→arm64).

Direct-exec for distroless / shell-less images (no SSH, no shell):

```clojure
(vm/run-image! {:image "gcr.io/distroless/python3-debian13:latest"
                :cmd ["/usr/bin/python3" "-c" "print('hi')"]
                :cache-dir "/tmp/cache"
                :project-dir "/path/to/your/project"})
;; => {:stdout "hi\n" :stderr "" :exit 0}
```

## API

| Function | Args | Returns | Errors |
|---|---|---|---|
| `check!` | — | `{:ok? true :results [...]}` | — |
| `start!` | `opts` | VM handle | throws if `check!` finds an `:error` |
| `exec` | `vm command & opts` | `{:stdout :stderr :exit}` | throws on timeout |
| `exec!` | `vm command & opts` | `{:stdout :stderr :exit}` | throws on nonzero exit or timeout |
| `alive?` | `vm` | boolean | — |
| `info` | `vm` | `{:image :port :rootfs :workspace :mounts :running?}` | — |
| `copy-to!` | `vm host-path guest-path` | nil | throws on scp failure |
| `copy-from!` | `vm guest-path host-path` | nil | throws on scp failure |
| `install-image!` | `image packages & opts` | `:ok` | — |
| `install!` | `vm packages` | `:ok` | — |
| `run-image!` | `opts` | `{:stdout :stderr :exit}` | throws on timeout |
| `start-daemon!` / `daemon-exec` / `daemon-run` / `stop-daemon!` | see `jolt-exec` | exec daemon (no SSH) | — |
| `attach!` | `vm` | exit status (blocks) | — |
| `save-snapshot!` | `vm name` | snapshot ref | — |
| `list-snapshots` | `vm` | vector of names | — |
| `restore-snapshot!` | `vm name` | nil | — |
| `delete-snapshot!` | `vm name` | nil | — |
| `reboot!` | `vm` | nil | — |
| `stop!` | `vm` | nil | — |
| `with-vm` (macro) | `[sym opts] & body` | body's value | — |

A VM stays booted until `stop!`, so an agent keeps one warm and `exec`s many
commands into the same rootfs. The CLI (`jolt run -m jolt.microvm.cli ...`)
exposes `check`, `exec`, `run`, `shell`, `install`, and `snapshots`; `exec`
streams output and exits with the guest's status, `run` throws on nonzero,
`shell` attaches interactively.

## Modules

- `jolt.microvm` — the API above.
- `jolt.microvm.cli` — the `jolt run -m` command-line interface.
- `jolt.microvm.rootfs` — base/prepare, alpine package install, mount + user/key/krun-config injection.
- `jolt.microvm.keys` — ephemeral + host ed25519 keys via `ssh-keygen`.
- `jolt.microvm.runner` — the subprocess that boots libkrun with all virtio-fs mounts.

## Status

beta — boot, exec/exec!, warm reuse, snapshots/restore, direct-exec
(distroless), daemon mode, egress, and attach are each exercised by the test
suite. Multi-arch is resolved but arm64 boot is only smoke-tested on x86_64
hosts.

## License

EPL-2.0

## Learn more

- [jolt-lang](https://github.com/jolt-lang/jolt) — the language and toolchain
- [libkrun](https://github.com/containers/libkrun) — the microVM runtime
- [jolt-libkrun](https://github.com/racklr-dev/jolt-libkrun) — the krun_* bindings this drives
- [jolt-oci](https://github.com/racklr-dev/jolt-oci) — the image pull + unpack layer
