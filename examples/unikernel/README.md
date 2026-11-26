# jolt-microvm unikernel (bare-metal lane)

Boot a *freestanding* kernel (no guest OS, no libc) under libkrun's
`boot-external!`. The guest is one ELF loaded at `0x100000`, talking to the
host over serial (COM1 `0x3F8` ↔ host stdin/stdout).

## What is proven

| Protocol | Status | Source | Notes |
|---|---|---|---|
| Linux-ABI ELF | booting | `linux-abi/`, `rust/` | **MVP.** libkrun loads the ELF, `rsp` → static stack, `call kmain`. |
| Gambit (freestanding Scheme) | **boots Scheme** | `gambit/` | `gsc -target C` → bare-metal ELF; runs `(display "BOOT-OK")` under libkrun. |
| Xen PVH | booting | `pvh/` | `.note.Xen` PHYS32_ENTRY + 32-bit entry. Alternative; not needed for MVP. |
| Unikraft / Solo5 | deferred | n/a | MULTIBOOT; libkrun 2.0 does not support it (would need a shim/loader). |

To exit, the host sends EOT (`0x04`), the kernel runs `ud2` (triple fault),
and libkrun reports a clean shutdown.

## The two lanes

### Guest Linux (the normal jolt-microvm path)

jolt already compiles Clojure → Scheme → native code (Chez primary, Gambit
secondary). Its binaries are self-contained but glibc-linked, so they run
*inside* any alpine image today. No unikernel work is needed for this lane.

### Bare metal (this folder)

Anything needing libc, the runtime linker, or Linux syscalls cannot boot via
`boot-external!`. The direction is to compile *jolt itself* freestanding, via
jolt → Gambit backend → `gsc -target C` → freestanding C. jolt's Gambit boot
already strips runtime `load` and the filesystem (its `js` target has the same
constraint), so the compiler side is close.

The bare-metal Gambit OS port is DONE and proven: see `gambit/` — a 2-line
Scheme program (`gsc -target C`) linked against the freestanding Gambit runtime
(bump heap + COM1 serial, no libc) boots under libkrun and prints `BOOT-OK`.
The full jolt kernel boot (live compiler + nREPL server) is also done there:
`./stage.sh serve` then `./nrepl-test.py` drives a COM1 bencode nREPL endpoint.
The hand-written mini-Lisp (`joltlisp.c` in `jolt-unikernel/`) is superseded.

## Rust kernel (current toolchain)

`rustup target add x86_64-unknown-none` ships precompiled `core`/`alloc`, so
there is no `build-std` and no `RUSTC_BOOTSTRAP`.

`asm!`/`global_asm!` default to **Intel** syntax on x86. Pass
`options(att_syntax)` (already done in `src/main.rs`).

```sh
cd rust && cargo build --release
# -> rust/target/x86_64-unknown-none/release/joltlisp   (~9.4 KB)
```

## C kernel (no rustup needed)

```sh
cd linux-abi
clang -c entry.S -o entry.o
clang -ffreestanding -nostdlib -fno-stack-protector -fno-pic -mno-red-zone -c kernel.c -o kernel.o
ld -nostdlib -Ttext=0x100000 -o kernel entry.o kernel.o
```

## PVH probe

Needs GNU `as`, because clang's integrated assembler rejects `OFFSET FLAT:`.

```sh
cd pvh
as pvh.S -o pvh.o
ld -nostdlib -Ttext=0x100000 -o pvh pvh.o
```

## Build and boot

```sh
./build.sh   # builds all three

cd /home/jm/projects/jolt/jolt-libkrun
KERNEL=/home/jm/projects/jolt/jolt-microvm/examples/unikernel/rust/target/x86_64-unknown-none/release/joltlisp \
  jolt run ../jolt-microvm/examples/unikernel/boot.clj
```

That one line is the whole boot API.

```clojure
(require '[jolt.libkrun :as krun])
(krun/boot-external! {:kernel "/abs/path/to/kernel" :cpus 1 :memory-mib 64})
```

The maintained harnesses in `jolt-libkrun/test/jolt/` are `unikernel_rust_test.clj`,
`unikernel_c_test.clj`, `unikernel_pvh_hello_test.clj`, `unikernel_echo_test.clj`,
`unikernel_hello_test.clj`, and `libkrun_probe_test.clj`.
