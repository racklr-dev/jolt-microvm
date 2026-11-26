/* kmain.c — freestanding Gambit entry: FPU+console init, ___setup, exit.
   Mirrors the setup tail of Gambit's main.c ___main, minus all option parsing. */
#define ___VERSION 409007
#include <stddef.h>
#include "gambit.h"
#include "os_base.h"
#include "setup.h"

typedef unsigned long long u64;
static inline u64 read_cr0(void){ u64 v; __asm__ volatile("mov %%cr0,%0" : "=r"(v)); return v; }
static inline void write_cr0(u64 v){ __asm__ volatile("mov %0,%%cr0" : : "r"(v)); }
static inline u64 read_cr4(void){ u64 v; __asm__ volatile("mov %%cr4,%0" : "=r"(v)); return v; }
static inline void write_cr4(u64 v){ __asm__ volatile("mov %0,%%cr4" : : "r"(v)); }

void bare_com1_init(void);
void bare_com1_write(const void *buf, size_t n);
void load_idt(void);

/* the linkfile linker for this boot, defined by boot_.c. Defaults to the
   2-line probe; the full jolt kernel boot passes -D___BOOT_LINKER=___LNK_unikernel_2d_main__. */
#ifndef ___BOOT_LINKER
#define ___BOOT_LINKER ___LNK_kernel_2d_probe__
#endif
extern ___mod_or_lnk ___BOOT_LINKER(___global_state); /* linkfile linker, not the module */

static void enable_fpu(void){
  u64 cr0 = read_cr0(); cr0 &= ~((1ULL << 2) | (1ULL << 3)); write_cr0(cr0); /* clear EM, TS */
  u64 cr4 = read_cr4(); cr4 |= (1ULL << 9) | (1ULL << 10); write_cr4(cr4);   /* OSFXSR, OSXMMEXCPT */
  __asm__ volatile("fninit");
}

static void putsz(const char *s){ while (*s) bare_com1_write(s++, 1); }
static void puthex(unsigned long long v){
  static const char h[] = "0123456789abcdef";
  char b[19]; int i; b[0]='0'; b[1]='x';
  for (i = 17; i >= 2; i--){ b[i] = h[v & 15]; v >>= 4; }
  b[18] = '\n'; bare_com1_write(b, 19);
}

void kmain(void){
  enable_fpu();
  bare_com1_init();
  load_idt();
  putsz("gambit-bare: kmain\n");

  static ___UCS_2STRING av[1] = { 0 };
  ___program_startup_info.argv = av;
  ___program_startup_info.script_line = 0;

  ___setup_params_struct p;
  ___setup_params_reset(&p);
  p.version = ___VERSION;
  p.argv = av;
  p.min_heap = 384UL * 1024 * 1024;
  p.max_heap = 384UL * 1024 * 1024;
  p.live_percent = 50;
  p.standard_level = 0;
  p.debug_settings = ___DEBUG_SETTINGS_INITIAL;
  p.linker = ___BOOT_LINKER; /* linkfile linker, not the module */

  putsz("gambit-bare: calling ___setup\n");
  ___SCMOBJ e = ___setup(&p);

  putsz("gambit-bare: ___setup returned\n");
  if (e != ___FIX(___NO_ERR)) {
    putsz("gambit-bare: setup error, raw=" );
    puthex((unsigned long long)(unsigned long)e);
    putsz("gambit-bare: setup error code=");
    puthex((unsigned long long)(long)___INT(e));
  }
  __asm__ volatile("ud2");
  for (;;) {}
}
