#!/bin/sh
# Build every proven jolt-unikernel example in this folder, then show how to boot.
set -eu
cd "$(dirname "$0")"

# rustup's toolchain has x86_64-unknown-none; nix cargo on PATH may not.
[ -d "$HOME/.cargo/bin" ] && export PATH="$HOME/.cargo/bin:$PATH"

echo "== Rust kernel (Linux-ABI, current toolchain) =="
( cd rust && cargo build --release )
RUST_KERNEL="$PWD/rust/target/x86_64-unknown-none/release/joltlisp"

echo "== C kernel (Linux-ABI, no rustup needed) =="
( cd linux-abi
  clang -c entry.S -o entry.o
  clang -ffreestanding -nostdlib -fno-stack-protector -fno-pic -mno-red-zone -c kernel.c -o kernel.o
  ld -nostdlib -Ttext=0x100000 -o kernel entry.o kernel.o )
C_KERNEL="$PWD/linux-abi/kernel"

echo "== PVH probe (Xen PVH, GNU as) =="
( cd pvh
  as pvh.S -o pvh.o
  ld -nostdlib -Ttext=0x100000 -o pvh pvh.o )
PVH_KERNEL="$PWD/pvh/pvh"

echo
echo "Built:"
ls -la "$RUST_KERNEL" "$C_KERNEL" "$PVH_KERNEL"
echo
echo "Boot (from the jolt-libkrun repo):"
echo "  cd /home/jm/projects/jolt/jolt-libkrun"
echo "  KERNEL=$RUST_KERNEL jolt run ../jolt-microvm/examples/unikernel/boot.clj"
echo "  KERNEL=$C_KERNEL    jolt run ../jolt-microvm/examples/unikernel/boot.clj"
echo "  KERNEL=$PVH_KERNEL  jolt run ../jolt-microvm/examples/unikernel/boot.clj"
