/* bare_os.c — freestanding OS port for Gambit: bump heap + COM1 console.
   malloc is a bump allocator over a static region (never frees); the GC heap
   is allocated once from it (min_heap == max_heap), so no realloc/grow path
   is exercised at boot. realloc is therefore a fresh alloc (no size tracking). */
#include <stddef.h>

typedef __SIZE_TYPE__ size_t;
void *memcpy(void*, const void*, size_t);
void *memset(void*, int, size_t);

/* ---- bump heap ---- */
static unsigned char heap_region[512UL * 1024 * 1024] __attribute__((aligned(4096)));
static size_t heap_used = 0;

void *malloc(size_t n){
  size_t a = (n + 15) & ~(size_t)15;
  if (heap_used + a > sizeof(heap_region)) return 0;
  void *p = heap_region + heap_used;
  heap_used += a;
  return p;
}
void free(void *p){ (void)p; }
void *calloc(size_t m, size_t n){
  size_t t = m * n;
  void *p = malloc(t);
  if (p) memset(p, 0, t);
  return p;
}
void *realloc(void *p, size_t n){ (void)p; return malloc(n); }

/* ---- COM1 serial (16550 at 0x3F8, matches libkrun stdio_serial) ---- */
typedef unsigned char u8;
typedef unsigned short u16;
#define COM1 0x3F8

static inline void outb(u16 port, u8 v){ __asm__ volatile("outb %0, %1" : : "a"(v), "Nd"(port)); }
static inline u8 inb(u16 port){ u8 v; __asm__ volatile("inb %1, %0" : "=a"(v) : "Nd"(port)); return v; }

void bare_com1_init(void){
  outb(COM1+1, 0x00);
  outb(COM1+3, 0x80);
  outb(COM1+0, 0x03);
  outb(COM1+1, 0x00);
  outb(COM1+3, 0x03);
  outb(COM1+2, 0xC7);
  outb(COM1+4, 0x0B);
}
static int com1_tx_ready(void){ return inb(COM1+5) & 0x20; }
static int com1_rx_ready(void){ return inb(COM1+5) & 0x01; }
void bare_com1_putc(char c){ while (!com1_tx_ready()) {} outb(COM1, (u8)c); }
char bare_com1_getc(void){ while (!com1_rx_ready()) {} return (char)inb(COM1); }
int bare_com1_rx_ready(void){ return com1_rx_ready(); }
int bare_com1_try_getc(void){ return com1_rx_ready() ? (int)(unsigned char)inb(COM1) : -1; }
void bare_com1_write(const void *buf, size_t n){
  const char *b = (const char*)buf;
  size_t i;
  for (i = 0; i < n; i++) bare_com1_putc(b[i]);
}
