    .text

.macro EXC n
    .globl exc_\n
exc_\n:
    pushq $\n
    jmp exc_common
.endm

    EXC 0
    EXC 1
    EXC 2
    EXC 3
    EXC 4
    EXC 5
    EXC 6
    EXC 7
    EXC 8
    EXC 9
    EXC 10
    EXC 11
    EXC 12
    EXC 13
    EXC 14
    EXC 15
    EXC 16
    EXC 17
    EXC 18
    EXC 19
    EXC 20
    EXC 21
    EXC 22
    EXC 23
    EXC 24
    EXC 25
    EXC 26
    EXC 27
    EXC 28
    EXC 29
    EXC 30
    EXC 31

exc_common:
    movq %rsp, %rdi
    call exc_handler
    cli
1:  hlt
    jmp 1b
