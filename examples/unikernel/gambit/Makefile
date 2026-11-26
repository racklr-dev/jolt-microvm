CC := gcc
CFLAGS := -I include -fno-stack-protector -fno-pie -fno-pic -fno-strict-aliasing -fwrapv -fno-trapping-math -fno-math-errno -fomit-frame-pointer -mno-red-zone -D_GNU_SOURCE -DHAVE_CONFIG_H -D___SINGLE_THREADED_VMS -D___USE_NO_THREAD_SYSTEM -D___NO_THREAD_LOCAL_STORAGE_CLASS -DUSE_POSIX -O1 -g0
# generated Gambit C calls memmove/memset/str*/math names without declaring them;
# force the declarations in, but only for the generated files (our own stubs
# define those very names, so the shim must not apply there).
SHIM := -include shim.h
BOOTCFLAGS := -I include -include shim.h -fno-stack-protector -fno-pie -fno-pic -fno-strict-aliasing -fwrapv -fno-trapping-math -fno-math-errno -fomit-frame-pointer -mno-red-zone -D_GNU_SOURCE -DHAVE_CONFIG_H -D___SINGLE_THREADED_VMS -D___USE_NO_THREAD_SYSTEM -D___NO_THREAD_LOCAL_STORAGE_CLASS -DUSE_POSIX -O0 -g0
LDFLAGS := -static -nostdlib -no-pie -Wl,-e,_start -Wl,-Ttext=0x100000
# the linkfile linker the entry (kmain.c) passes to ___setup; the full jolt
# boot overrides this to ___LNK_unikernel_2d_main__ via stage.sh.
BOOT_LINKER ?= ___LNK_serve__

CORE := _kernel _io _num _std _nonstd _eval _repl _thread _system _module _gambit mem c_intf setup os_base os_setup os_thread os_shell os_io os_tty os_files os_time os_dyn actlog
OBJS := $(addprefix src/,$(addsuffix .o,$(CORE))) src/boot.o src/boot_.o src/serve.o kmain.o bare_os.o bare_libc.o bare_stubs.o entry.o idt.o idt_stubs.o

all: jolt-bare.elf

src/%.o: src/%.c
	$(CC) $(CFLAGS) $(SHIM) -c -o $@ $<

# the generated boot.c is one ~100MB translation unit; gcc explodes on it
# (1h+, 17GB RSS). clang handles huge generated C far faster and lighter.
BOOT_CC ?= $(CC)
src/boot.o src/boot_.o: src/%.o: src/%.c
	$(BOOT_CC) $(BOOTCFLAGS) -c -o $@ $<

kmain.o: kmain.c
	$(CC) $(CFLAGS) -D___BOOT_LINKER=$(BOOT_LINKER) -c -o $@ $<

bare_os.o: bare_os.c
	$(CC) $(CFLAGS) -c -o $@ $<

bare_libc.o: bare_libc.c
	$(CC) $(CFLAGS) -c -o $@ $<

bare_stubs.o: bare_stubs.c
	$(CC) $(CFLAGS) -c -o $@ $<

entry.o: entry.S
	$(CC) -c -o $@ $<

idt.o: idt.c
	$(CC) $(CFLAGS) -c -o $@ $<

idt_stubs.o: idt.S
	$(CC) -c -o $@ $<

jolt-bare.elf: $(OBJS)
	$(CC) $(LDFLAGS) -o $@ $(OBJS)

.PHONY: all
