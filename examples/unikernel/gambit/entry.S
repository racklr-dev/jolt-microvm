    .section .bss
    .align 16
stack_bottom:
    .skip 16777216
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
