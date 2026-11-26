/* jolt-unikernel: freestanding C kernel proving clang->libkrun Linux-ABI boot.
   Serial: COM1 (0x3F8) <-> host stdin/stdout. Exit: EOT (0x04) -> ud2 triple fault. */
typedef unsigned long  u64;
typedef unsigned int   u32;
typedef unsigned short u16;
typedef unsigned char  u8;
typedef long           i64;

#define COM1 0x3F8

static inline void outb(u16 port, u8 v) {
  __asm__ volatile("outb %0, %1" : : "a"(v), "Nd"(port));
}
static inline u8 inb(u16 port) {
  u8 v;
  __asm__ volatile("inb %1, %0" : "=a"(v) : "Nd"(port));
  return v;
}

static void serial_init(void) {
  outb(COM1+1, 0x00);  /* disable interrupts */
  outb(COM1+3, 0x80);  /* DLAB */
  outb(COM1+0, 0x03);  /* divisor low  (38400) */
  outb(COM1+1, 0x00);  /* divisor high */
  outb(COM1+3, 0x03);  /* 8N1 */
  outb(COM1+2, 0xC7);  /* FIFO enable+clear */
  outb(COM1+4, 0x0B);  /* IRQs, RTS/DSR */
}

static int tx_ready(void) { return inb(COM1+5) & 0x20; }
static int rx_ready(void) { return inb(COM1+5) & 0x01; }

static void putc(char c) {
  while (!tx_ready()) {}
  outb(COM1, (u8)c);
}
static char getc(void) {
  while (!rx_ready()) {}
  return (char)inb(COM1);
}

static void puts(const char *s) {
  for (; *s; s++) putc(*s);
}

void kmain(void) {
  serial_init();
  puts("jolt-unikernel: C kernel booted (Linux-ABI)\n");
  for (;;) {
    char c = getc();
    if (c == 0x04) break;  /* EOT: exit */
    putc(c);
  }
  puts("bye\n");
  __asm__ volatile("ud2");
}
