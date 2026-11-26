/* bare_libc.c — minimal freestanding libc for the Gambit VM core.
   Only the symbols the generated C references. exp/log/pow/sqrt are stubs:
   they are the numeric tower, not reached at boot — replace before jolt math
   is exercised (fabs/floor/modf/ldexp/frexp are real, bit-level). */
#include <stddef.h>

typedef __SIZE_TYPE__ size_t;

void *malloc(size_t n);

void *memcpy(void *d, const void *s, size_t n){
  unsigned char *dd = (unsigned char*)d;
  const unsigned char *ss = (const unsigned char*)s;
  size_t i;
  for (i = 0; i < n; i++) dd[i] = ss[i];
  return d;
}
void *memmove(void *d, const void *s, size_t n){
  unsigned char *dd = (unsigned char*)d;
  const unsigned char *ss = (const unsigned char*)s;
  size_t i;
  if (dd < ss) { for (i = 0; i < n; i++) dd[i] = ss[i]; }
  else if (dd > ss) { for (i = n; i > 0; i--) dd[i-1] = ss[i-1]; }
  return d;
}
void *memset(void *d, int c, size_t n){
  unsigned char *dd = (unsigned char*)d;
  size_t i;
  for (i = 0; i < n; i++) dd[i] = (unsigned char)c;
  return d;
}
size_t strlen(const char *s){ size_t n = 0; while (s[n]) n++; return n; }
int strcmp(const char *a, const char *b){
  while (*a && *a == *b) { a++; b++; }
  return (unsigned char)*a - (unsigned char)*b;
}
int strncmp(const char *a, const char *b, size_t n){
  size_t i = 0;
  while (i < n && a[i] && a[i] == b[i]) i++;
  return i == n ? 0 : (unsigned char)a[i] - (unsigned char)b[i];
}
int memcmp(const void *a, const void *b, size_t n){
  const unsigned char *aa = (const unsigned char*)a, *bb = (const unsigned char*)b;
  size_t i;
  for (i = 0; i < n; i++) if (aa[i] != bb[i]) return aa[i] - bb[i];
  return 0;
}
char *strchr(const char *s, int c){
  while (*s) { if (*s == (char)c) return (char*)s; s++; }
  return c == 0 ? (char*)s : 0;
}
char *strrchr(const char *s, int c){
  const char *r = 0;
  while (*s) { if (*s == (char)c) r = s; s++; }
  return c == 0 ? (char*)s : (char*)r;
}
char *strcpy(char *d, const char *s){ char *r = d; while ((*d++ = *s++)); return r; }
char *strncpy(char *d, const char *s, size_t n){
  size_t i;
  for (i = 0; i < n && s[i]; i++) d[i] = s[i];
  for (; i < n; i++) d[i] = 0;
  return d;
}
char *strcat(char *d, const char *s){ char *r = d; while (*d) d++; while ((*d++ = *s++)); return r; }
char *strdup(const char *s){ size_t n = strlen(s) + 1; char *p = malloc(n); if (p) memcpy(p, s, n); return p; }

/* ---- math ---- */
double fabs(double x){ union { double d; unsigned long long u; } u; u.d = x; u.u &= 0x7fffffffffffffffULL; return u.d; }
double floor(double x){ long long i = (long long)x; double f = (double)i; if (x >= 0.0) return f; return (x == f) ? f : f - 1.0; }
double ceil(double x){ long long i = (long long)x; double f = (double)i; if (x <= 0.0) return f; return (x == f) ? f : f + 1.0; }
double trunc(double x){ return (double)(long long)x; }
double modf(double x, double *ip){ double t = trunc(x); *ip = t; return x - t; }
double fmod(double x, double y){ long long q = (long long)(x / y); return x - (double)q * y; }
double copysign(double x, double y){ union { double d; unsigned long long u; } a, b; a.d = x; b.d = y; a.u = (a.u & 0x7fffffffffffffffULL) | (b.u & 0x8000000000000000ULL); return a.d; }
double ldexp(double x, int n){
  union { double d; unsigned long long u; } u; u.d = x;
  int e = (int)((u.u >> 52) & 0x7ff);
  if (e == 0 || e == 0x7ff) return x;
  e += n;
  if (e >= 0x7ff) return x < 0.0 ? -1.0/0.0 : 1.0/0.0;
  if (e <= 0) return 0.0;
  u.u = (u.u & 0x800fffffffffffffULL) | ((unsigned long long)e << 52);
  return u.d;
}
double frexp(double x, int *e){
  union { double d; unsigned long long u; } u; u.d = x;
  int exp = (int)((u.u >> 52) & 0x7ff);
  if (exp == 0x7ff || x == 0.0) { *e = 0; return x; }
  if (exp == 0) { *e = 0; return x; }
  *e = exp - 1022;
  u.u = (u.u & 0x800fffffffffffffULL) | (0x3feULL << 52);
  return u.d;
}
double fmax(double a, double b){ return a > b ? a : b; }
double fmin(double a, double b){ return a < b ? a : b; }
int isnan(double x){ union { double d; unsigned long long u; } u; u.d = x; return (u.u & 0x7ff0000000000000ULL) == 0x7ff0000000000000ULL && (u.u & 0x000fffffffffffffULL) != 0; }
int isinf(double x){ union { double d; unsigned long long u; } u; u.d = x; return (u.u & 0x7fffffffffffffffULL) == 0x7ff0000000000000ULL; }
int finite(double x){ return !isnan(x) && !isinf(x); }

/* stubs — numeric tower only, not reached at boot */
double sqrt(double x){ return 0.0; }
double exp(double x){ return 0.0; }
double log(double x){ return 0.0; }
double pow(double x, double y){ return 0.0; }
double sin(double x){ return 0.0; }
double cos(double x){ return 0.0; }
double tan(double x){ return 0.0; }
double asin(double x){ return 0.0; }
double acos(double x){ return 0.0; }
double atan(double x){ return 0.0; }
double atan2(double y, double x){ return 0.0; }
double sinh(double x){ return 0.0; }
double cosh(double x){ return 0.0; }
double tanh(double x){ return 0.0; }
double expm1(double x){ return 0.0; }
double log1p(double x){ return 0.0; }
double log10(double x){ return 0.0; }
double cbrt(double x){ return 0.0; }
double hypot(double x, double y){ return 0.0; }
double round(double x){ return x >= 0.0 ? floor(x + 0.5) : ceil(x - 0.5); }

/* ---- stdio stubs (error paths only) ---- */
static int bare_errno = 0;
int *__errno_location(void){ return &bare_errno; }
int printf(const char *fmt, ...){ (void)fmt; return 0; }
int fprintf(void *f, const char *fmt, ...){ (void)f; (void)fmt; return 0; }
int sprintf(char *s, const char *fmt, ...){ (void)fmt; if (s) s[0] = 0; return 0; }
int snprintf(char *s, size_t n, const char *fmt, ...){ (void)fmt; if (s && n) s[0] = 0; return 0; }
int vsnprintf(char *s, size_t n, const char *fmt, __builtin_va_list ap){ (void)fmt; (void)ap; if (s && n) s[0] = 0; return 0; }
int vfprintf(void *f, const char *fmt, __builtin_va_list ap){ (void)f; (void)fmt; (void)ap; return 0; }
int puts(const char *s){ (void)s; return 0; }
int putchar(int c){ return c; }
int fputs(const char *s, void *f){ (void)s; (void)f; return 0; }
int fputc(int c, void *f){ (void)c; (void)f; return 0; }
int fgetc(void *f){ (void)f; return -1; }
size_t fwrite(const void *p, size_t a, size_t b, void *f){ (void)p; (void)a; (void)f; return b; }
size_t fread(void *p, size_t a, size_t b, void *f){ (void)p; (void)a; (void)f; return 0; }
