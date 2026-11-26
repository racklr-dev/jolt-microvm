.intel_syntax noprefix
.code32
.section .note.Xen, "a", @note
    .long 4              # namesz = 4
    .long 4              # descsz = 4
    .long 18             # type = XEN_ELFNOTE_PHYS32_ENTRY
    .asciz "Xen"        # name "Xen\0"
    .long _start         # desc: 32-bit physical entry point
    .p2align 2

.section .text
.global _start
.code32
_start:
    lea   esi, [msg]
    mov   ecx, OFFSET FLAT:msg_len
1:  mov   dx, 0x3FD              # COM1 LSR
    in    al, dx
    test  al, 0x20               # wait for THR empty
    jz    1b
    lodsb                       # al = [esi], esi++
    mov   dx, 0x3F8              # COM1 THR
    out   dx, al
    dec   ecx
    jnz   1b
exit:
    ud2                         # #UD -> double fault -> triple fault -> shutdown
.section .rodata
msg:
    .ascii "jolt: PVH unikernel booted\n"
.set msg_len, . - msg
