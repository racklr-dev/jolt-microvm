# jolt nREPL unikernel — COM1 wire protocol

This example boots the full jolt kernel (live `jolt-compile-eval`) as a
freestanding Gambit unikernel and speaks a **bencode nREPL** dialect over COM1.
It is the seed of the jolt control-plane lane: one guest = one long-lived
session that evaluates jolt forms and reports their printed output, value, and
errors — exactly what a web REPL, an agent, or an Erlang-style VM supervisor
needs to drive a unikernel.

## Transport

- The libkrun guest's COM1 UART (`0x3F8`) is wired to the host process's
  stdin/stdout by `booter.c` (external-kernel mode). Full-duplex, no fifo.
- Boot output and the nREPL stream share the same byte channel. The server
  emits an **ASCII preamble** and then the line `READY`; everything after
  `READY` is pure bencode, one request or response per top-level dict.

```text
gambit-bare: kmain
gambit-bare: calling ___setup
boot: backend target -> :gambit
BOOT-OK
SERVE: jolt COM1 nREPL endpoint
eval> (+ 1 2) => 3
eval> {:a 1 :b 2} => {:a 1, :b 2}
eval> (map inc [1 2 3]) => (2 3 4)
READY
<benbyte stream begins>
```

A host driver must read byte-at-a-time until it has consumed a complete bencode
value (the parser in `nrepl-test.py` shows the shape). The `READY` line is the
handshake: send nothing before it.

## Bencode

Each message is one bencode **dict** with string keys, emitted in
bencode-sorted key order. The reader accepts any key order. Integers are
supported by the codec but unused in this protocol.

Known caveat: the string prefix length currently counts **characters**, not
UTF-8 bytes (`(string-length s)` in Gambit). The transport is ASCII-only for
now; UTF-8 payloads need a byte-count fix in `serve.scm`'s `bw-str`.

## Ops

### `eval`

```text
request:  {"op": "eval", "code": "<form>", "id": "<id>", "session": "<sid>"}
response: {"id": .., "ns": "user", "op": "eval",
           "out": "<captured stdout, only if non-empty>",
           "session": .., "value": "<printed value or \"\" on error>",
           "err": "<only on error>", "status": ["done"]}
```

- `session` is optional; absent means the default session `"0"`.
- Stdout produced by the form is captured (`call-with-output-string` +
  `current-output-port`) and returned in `out` **before** the value — identical
  to `repl-main.ss`'s `repl-step`.
- On a thrown error: `err` holds the rendered error, `value` is `""`, and
  `status` is `["eval-error" "done"]`.
- The error text matches jolt's REPL rendering: `ExceptionInfo` prints as
  `clojure.lang.ExceptionInfo: <message>`, resolve failures as
  `clojure.lang.ExceptionInfo: Unable to resolve symbol: <sym> ...`.

### `clone`

```text
request:  {"op": "clone", "id": .., "session": "<sid-to-clone>"}
response: {"id": .., "new-session": "<fresh-sid>", "session": "<fresh-sid>",
           "status": ["done"]}
```

Creates a session that inherits the cloned session's namespace (default
`user`). Use `new-session` as the `session` field of later requests.

### `describe`

```text
request:  {"op": "describe", "id": ..}
response: {"id": .., "ops": {"clone": {}, "close": {}, "describe": {},
                             "eval": {}},
           "session": .., "status": ["done"],
           "versions": {"jolt": {"version-string": "0.1.0"},
                        "nrepl": {"version-string": "0.9.0"}}}
```

Advertises the supported op set and versions, so a real nREPL client can
negotiate capability.

### `close`

```text
request:  {"op": "close", "id": .., "session": "<sid>"}
response: {"id": .., "session": .., "status": ["done" "session-closed"]}
```

Drops the session. Unknown-session evals currently fall back to `"user"`
rather than erroring.

### Unknown op

Responds `{"id": .., "session": .., "status": ["done"]}` with no `value`.

## Sessions

- Default session `"0"` is the `user` namespace.
- Each session tracks its own namespace symbol; `clone` copies the parent's ns.
- Session ids are small incrementing integers rendered as strings (`"1"`, `"2"`, …).
- Not yet implemented: session-local var bindings, interrupts, `load-file`,
  `stdin`, `complete`. The minimal op set above is the deliberate first cut.

## Build, run, test

Prerequisites (present on this host):

- `~/.local/bin/gsc` — Gambit v4.9.7, built **full**, with the full build tree
  at `/tmp/gambit-full` (`lib/*.c`, `include/*.h`).
- libkrun at `/tmp/libkrun` (boot/test steps only).
- `clang` (the generated ~100MB `boot.c` makes gcc explode).

```sh
cd jolt-microvm/examples/unikernel/gambit

./stage.sh serve            # compile + link + make -> jolt-bare.elf
./stage.sh serve boot       # also boot it under libkrun

./nrepl-test.py             # boots + drives the endpoint, asserts ALL PASS
```

The reproducible recipe inside `stage.sh` (from a clean `src/`):

```sh
# boot-probe.scm compiles in jolt-lang (its ##include "boot-full.ss" resolves
# next to it); serve.scm compiles here in the example (self-contained).
(cd jolt-lang/host/gambit && gsc -target C -c boot-probe.scm)  # -> boot-probe.c
gsc -target C -c serve.scm                                     # -> serve.c
gsc -link jolt-lang/host/gambit/boot-probe.c serve.c           # -> serve_.c
# copy boot-probe.c -> src/boot.c, serve.c -> src/serve.c, serve_.c -> src/boot_.c
# BOOT_LINKER=___LNK_serve__  (kmain passes it to ___setup)
make -j18 BOOT_CC=clang CC=clang BOOT_LINKER=___LNK_serve__
```

`gsc -link a.c b.c` emits the linkfile as **`b_.c`** (named after the last
module) — that is why `serve_.c`, not `boot-probe_.c`, is the linkfile.

## Files

| File | Role |
|---|---|
| `NREPL.md` | this protocol document |
| `nrepl-test.py` | boots the ELF, drives bencode, asserts eval/out/err/sessions |
| `stage.sh` | `probe` / `serve` staging + build + boot |
| `serve.scm` | the COM1 nREPL server: bencode codec + eval/clone/describe/close |
| `kernel-probe.scm` | 2-line probe boot (`BOOT-OK`) |
| `kmain.c` | freestanding entry: FPU + COM1 + `___setup` |
| `bare_os.c` | bump-heap malloc + 16550 COM1 UART |
| `bare_libc.c` | mem*/str*/math the generated C needs |
| `bare_stubs.c` | setjmp/longjmp + ~100 POSIX stubs; write -> COM1 |
| `entry.S` / `idt.c` / `idt.S` | `_start` + 32-vector fault dumper |
| `shim.h` | force-included declarations for the generated C |
| `booter.c` | libkrun external-kernel loader (host side) |
| `Makefile` | builds `jolt-bare.elf` from `src/` + the files above |

The server source `serve.scm` (bencode codec + nREPL loop) lives in this
example. The kernel boot `boot-probe.scm` (full profile, live compiler) comes
from a jolt-lang checkout and is the example's only jolt-lang dependency.
`src/` and `include/` are generated by `stage.sh` and not vendored.
