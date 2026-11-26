/* idt.c — minimal IDT: every exception dumps vector + fault frame over COM1,
   then halts (cli + hlt loop). Diagnostic for the bare-metal VM crash. */
#include <stddef.h>

typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
typedef unsigned long long u64;

void bare_com1_write(const void *buf, size_t n);

struct idt_entry {
  u16 off_lo; u16 sel; u8 ist; u8 flags; u16 off_mid; u32 off_hi; u32 zero;
} __attribute__((packed));
struct idt_ptr { u16 limit; u64 base; } __attribute__((packed));

static struct idt_entry idt[256];

extern void exc_0(void); extern void exc_1(void); extern void exc_2(void); extern void exc_3(void);
extern void exc_4(void); extern void exc_5(void); extern void exc_6(void); extern void exc_7(void);
extern void exc_8(void); extern void exc_9(void); extern void exc_10(void); extern void exc_11(void);
extern void exc_12(void); extern void exc_13(void); extern void exc_14(void); extern void exc_15(void);
extern void exc_16(void); extern void exc_17(void); extern void exc_18(void); extern void exc_19(void);
extern void exc_20(void); extern void exc_21(void); extern void exc_22(void); extern void exc_23(void);
extern void exc_24(void); extern void exc_25(void); extern void exc_26(void); extern void exc_27(void);
extern void exc_28(void); extern void exc_29(void); extern void exc_30(void); extern void exc_31(void);

static void set_entry(int n, void *fn){
  u64 a = (u64)fn;
  idt[n].off_lo = a & 0xffff; idt[n].off_mid = (a >> 16) & 0xffff; idt[n].off_hi = (a >> 32);
  idt[n].sel = 0x08;
  idt[n].ist = 0;
  idt[n].flags = 0x8E; /* present, ring 0, interrupt gate */
  idt[n].zero = 0;
}

static void hex64(u64 v){
  char buf[17]; int i;
  buf[16] = 0;
  for (i = 15; i >= 0; i--){ int d = v & 15; buf[i] = d < 10 ? '0' + d : 'a' + d - 10; v >>= 4; }
  bare_com1_write(buf, 16);
}
static void putsz(const char *s){ while (*s) bare_com1_write(s++, 1); }

/* frame: [0]=vector (pushed by stub), [1]=error code or RIP, then cs/rflags...
   For vectors WITH error code: [1]=err, [2]=rip. Without: [1]=rip, [2]=cs. */
static u64 read_cr2(void){ u64 v; __asm__ volatile("mov %%cr2,%0" : "=r"(v)); return v; }

void exc_handler(u64 *f){
  int v = (int)f[0];
  u64 cr2 = read_cr2();
  u64 rsp_at_fault = f[5];
  u64 pc_local = *(u64*)(rsp_at_fault + 0xcd8);
  u64 ps = *(u64*)(rsp_at_fault + 0x8);
  u64 ps_pc = ps ? *(u64*)(ps + 0x60) : 0;
  u64 proc_reg = *(u64*)(rsp_at_fault + 0x18);
  u64 proc_hdr = proc_reg ? *(u64*)(proc_reg - 1) : 0;
  u64 proc_code = proc_reg ? *(u64*)(proc_reg + 7) : 0;
  u64 c90 = *(u64*)(rsp_at_fault + 0xc90);
  u64 h_notproc_glo = ps ? *(u64*)(ps + 0xc0) : 0;
  u64 h_stacklim = ps ? *(u64*)(ps + 0xa8) : 0;
  u64 h_notproc = ps ? *(u64*)(ps + 0xb8) : 0;
  putsz("EXC ");
  { char d = '0' + (v / 10); char u = '0' + (v % 10); bare_com1_write(&d, 1); bare_com1_write(&u, 1); }
  putsz(" f1="); hex64(f[1]);
  putsz(" f2="); hex64(f[2]);
  putsz(" f3="); hex64(f[3]);
  putsz(" cr2="); hex64(cr2);
  putsz("\n  pc="); hex64(pc_local);
  putsz(" ps="); hex64(ps);
  putsz(" ps_pc="); hex64(ps_pc);
  putsz("\n  proc="); hex64(proc_reg);
  putsz(" hdr="); hex64(proc_hdr);
  putsz(" code="); hex64(proc_code);
  putsz("\n  c90="); hex64(c90);
  putsz(" h_npglo="); hex64(h_notproc_glo);
  putsz(" h_stacklim="); hex64(h_stacklim);
  putsz(" h_notproc="); hex64(h_notproc);
  putsz("\n");
}

void load_idt(void){
  void *fns[32] = { exc_0,exc_1,exc_2,exc_3,exc_4,exc_5,exc_6,exc_7,exc_8,exc_9,
    exc_10,exc_11,exc_12,exc_13,exc_14,exc_15,exc_16,exc_17,exc_18,exc_19,
    exc_20,exc_21,exc_22,exc_23,exc_24,exc_25,exc_26,exc_27,exc_28,exc_29,exc_30,exc_31 };
  int i;
  for (i = 0; i < 32; i++) set_entry(i, fns[i]);
  struct idt_ptr p = { .limit = sizeof(idt) - 1, .base = (u64)idt };
  __asm__ volatile("lidt %0" : : "m"(p));
}
