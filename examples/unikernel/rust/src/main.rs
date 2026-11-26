#![no_std]
#![no_main]

use core::arch::{asm, global_asm};

global_asm!(r#"
.section .bss
.align 16
stack_bottom: .skip 65536
stack_top:
.text
.globl _start
.type _start,@function
_start:
    mov $stack_top, %rsp
    xor %rbp, %rbp
    call kmain
1:  hlt
    jmp 1b
.size _start, . - _start
"#, options(att_syntax));

#[panic_handler]
fn panic(_info: &core::panic::PanicInfo) -> ! {
    loop {}
}

const COM1: u16 = 0x3F8;

fn outb(port: u16, v: u8) {
    unsafe { asm!("outb %al, %dx", in("dx") port, in("al") v, options(att_syntax, nomem, nostack, preserves_flags)); }
}
fn inb(port: u16) -> u8 {
    let v: u8;
    unsafe { asm!("inb %dx, %al", in("dx") port, out("al") v, options(att_syntax, nomem, nostack, preserves_flags)); }
    v
}

fn ser_init() {
    outb(COM1 + 1, 0x00);
    outb(COM1 + 3, 0x80);
    outb(COM1 + 0, 0x03);
    outb(COM1 + 1, 0x00);
    outb(COM1 + 3, 0x03);
    outb(COM1 + 2, 0xC7);
    outb(COM1 + 4, 0x0B);
}

fn tx_rdy() -> bool { inb(COM1 + 5) & 0x20 != 0 }
fn rx_rdy() -> bool { inb(COM1 + 5) & 0x01 != 0 }

fn putc(c: u8) {
    while !tx_rdy() {}
    outb(COM1, c);
}

fn getc() -> u8 {
    while !rx_rdy() {}
    inb(COM1)
}

fn puts(s: &str) {
    for &b in s.as_bytes() { putc(b); }
}

#[no_mangle]
pub extern "C" fn kmain() -> ! {
    ser_init();
    puts("jolt-unikernel: Rust kernel booted (Linux-ABI)\n");
    loop {
        let c = getc();
        if c == 0x04 { break; }
        putc(c);
    }
    puts("bye\n");
    unsafe { asm!("ud2"); }
    loop {}
}
