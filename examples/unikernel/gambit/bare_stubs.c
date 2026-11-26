/* bare_stubs.c — the freestanding libc/POSIX surface the Gambit runtime pulls
   in. Working: setjmp/longjmp (the VM's continuation switch) and write/read on
   fds 0/1/2 -> COM1 serial. Everything else fails cleanly (no files, no net,
   no processes, no clock) so the runtime degrades instead of faulting. */
#include <stddef.h>

typedef __SIZE_TYPE__ size_t;
typedef long ssize_t;

void bare_com1_write(const void *buf, size_t n);
char bare_com1_getc(void);

/* ---- setjmp/longjmp, glibc x86-64 __jmp_buf layout (8 words) ---- */
typedef unsigned long jmpword;
int _setjmp(void *env){
  jmpword *j = (jmpword*)env;
  __asm__ volatile(
    "movq %%rbx, 0(%0)\n\t"
    "movq %%rbp, 8(%0)\n\t"
    "movq %%r12, 16(%0)\n\t"
    "movq %%r13, 24(%0)\n\t"
    "movq %%r14, 32(%0)\n\t"
    "movq %%r15, 40(%0)\n\t"
    "leaq 8(%%rsp), %%rax\n\t"
    "movq %%rax, 48(%0)\n\t"
    "movq (%%rsp), %%rax\n\t"
    "movq %%rax, 56(%0)\n\t"
    "xorl %%eax, %%eax\n\t"
    : : "r"(j) : "rax", "memory");
  return 0;
}
int setjmp(void *env){ return _setjmp(env); }
void longjmp(void *env, int val){
  jmpword *j = (jmpword*)env;
  if (val == 0) val = 1;
  __asm__ volatile(
    "movq 0(%0), %%rbx\n\t"
    "movq 8(%0), %%rbp\n\t"
    "movq 16(%0), %%r12\n\t"
    "movq 24(%0), %%r13\n\t"
    "movq 32(%0), %%r14\n\t"
    "movq 40(%0), %%r15\n\t"
    "movq 48(%0), %%rsp\n\t"
    "movq 56(%0), %%rcx\n\t"
    "movl %1, %%eax\n\t"
    "jmp *%%rcx\n\t"
    : : "r"(j), "r"(val) : "memory");
  __builtin_unreachable();
}
void __longjmp_chk(void *env, int val, size_t sz){ (void)sz; longjmp(env, val); }

/* ---- console: fd 0/1/2 -> COM1 ---- */
ssize_t write(int fd, const void *buf, size_t n){
  if (fd == 1 || fd == 2){ bare_com1_write(buf, n); return (ssize_t)n; }
  return -1;
}
ssize_t read(int fd, void *buf, size_t n){
  if (fd == 0){ char *b = (char*)buf; size_t i; for (i = 0; i < n; i++) b[i] = bare_com1_getc(); return (ssize_t)n; }
  return -1;
}
ssize_t __read_chk(int fd, void *buf, size_t n, size_t buflen){ (void)buflen; return read(fd, buf, n); }
int isatty(int fd){ (void)fd; return 1; }
int select(int nfds, void *r, void *w, void *e, void *tv){ (void)nfds; (void)r; (void)w; (void)e; (void)tv; return 0; }

/* ---- process/environment ---- */
void exit(int status){ (void)status; __asm__ volatile("ud2"); for(;;){} }
void _exit(int status){ (void)status; __asm__ volatile("ud2"); for(;;){} }
int getpid(void){ return 1; }
int getppid(void){ return 1; }
long sysconf(int n){ (void)n; return -1; }
int sched_getcpu(void){ return 0; }
char *getenv(const char *name){ (void)name; return 0; }
char **environ = 0;
int execvp(const char *f, char *const a[]){ (void)f; (void)a; return -1; }
int fork(void){ return -1; }
int waitpid(int p, int *s, int o){ (void)p; (void)s; (void)o; return -1; }
int setsid(void){ return -1; }
int pipe(int f[2]){ (void)f; return -1; }

/* ---- filesystem: everything fails ---- */
int open(const char *p, int f, ...){ (void)p; (void)f; return -1; }
int openat(int d, const char *p, int f, ...){ (void)d; (void)p; (void)f; return -1; }
int close(int fd){ (void)fd; return 0; }
int dup(int fd){ (void)fd; return -1; }
int dup2(int a, int b){ (void)a; (void)b; return -1; }
long lseek(int fd, long off, int w){ (void)fd; (void)off; (void)w; return 0; }
int fcntl(int fd, int cmd, ...){ (void)fd; (void)cmd; return 0; }
int ioctl(int fd, unsigned long req, ...){ (void)fd; (void)req; return 0; }
int fsync(int fd){ (void)fd; return -1; }
int chdir(const char *p){ (void)p; return -1; }
char *getcwd(char *b, size_t n){ if (b && n) b[0] = 0; return b; }
char *__getcwd_chk(char *b, size_t n, size_t bn){ (void)bn; return getcwd(b, n); }
int rmdir(const char *p){ (void)p; return -1; }
int unlinkat(int d, const char *p, int f){ (void)d; (void)p; (void)f; return -1; }
int mkdirat(int d, const char *p, int m){ (void)d; (void)p; (void)m; return -1; }
int mkfifoat(int d, const char *p, int m){ (void)d; (void)p; (void)m; return -1; }
int linkat(int a, const char *b, int c, const char *d, int e){ (void)a; (void)b; (void)c; (void)d; (void)e; return -1; }
int symlinkat(const char *a, int b, const char *c){ (void)a; (void)b; (void)c; return -1; }
int readlinkat(int a, const char *b, char *c, size_t n){ (void)a; (void)b; (void)c; (void)n; return -1; }
int utimes(const char *p, const void *t){ (void)p; (void)t; return -1; }
void *opendir(const char *p){ (void)p; return 0; }
int fdopendir(int fd){ (void)fd; return 0; }
void *readdir(void *d){ (void)d; return 0; }
int closedir(void *d){ (void)d; return 0; }
int stat64(const char *p, void *s){ (void)p; (void)s; return -1; }
int fstat64(int fd, void *s){ (void)fd; (void)s; return -1; }
int lstat64(const char *p, void *s){ (void)p; (void)s; return -1; }
void *mmap64(void *a, size_t l, int p, int f, int fd, long long o){ (void)a; (void)l; (void)p; (void)f; (void)fd; (void)o; return 0; }
int munmap(void *a, size_t l){ (void)a; (void)l; return 0; }

/* ---- clock/timers: no-op ---- */
int clock_gettime(int c, void *t){ (void)c; (void)t; return 0; }
int clock_getres(int c, void *t){ (void)c; (void)t; return 0; }
int nanosleep(const void *r, void *rem){ (void)r; (void)rem; return 0; }
int getitimer(int w, void *v){ (void)w; (void)v; return 0; }
int setitimer(int w, const void *nv, void *ov){ (void)w; (void)nv; (void)ov; return 0; }
int getrusage(int w, void *u){ (void)w; (void)u; return 0; }

/* ---- signals: no-op ---- */
int sigaction(int s, const void *a, void *o){ (void)s; (void)a; (void)o; return 0; }
int sigprocmask(int h, const void *s, void *o){ (void)h; (void)s; (void)o; return 0; }
int sigemptyset(void *s){ (void)s; return 0; }
int sigaddset(void *s, int n){ (void)s; (void)n; return 0; }

/* ---- tty/termios: no-op ---- */
int tcgetattr(int fd, void *t){ (void)fd; (void)t; return 0; }
int tcsetattr(int fd, int a, const void *t){ (void)fd; (void)a; (void)t; return 0; }
int cfsetispeed(void *t, unsigned long s){ (void)t; (void)s; return 0; }
int cfsetospeed(void *t, unsigned long s){ (void)t; (void)s; return 0; }
int openpty(int *m, int *s, char *n, const void *t, const void *w){ (void)m; (void)s; (void)n; (void)t; (void)w; return -1; }

/* ---- networking: everything fails ---- */
int socket(int a, int b, int c){ (void)a; (void)b; (void)c; return -1; }
int bind(int fd, const void *a, unsigned int l){ (void)fd; (void)a; (void)l; return -1; }
int listen(int fd, int b){ (void)fd; (void)b; return -1; }
int connect(int fd, const void *a, unsigned int l){ (void)fd; (void)a; (void)l; return -1; }
int accept(int fd, void *a, void *l){ (void)fd; (void)a; (void)l; return -1; }
long recv(int fd, void *b, size_t n, int f){ (void)fd; (void)b; (void)n; (void)f; return -1; }
long recvfrom(int fd, void *b, size_t n, int f, void *a, void *l){ (void)fd; (void)b; (void)n; (void)f; (void)a; (void)l; return -1; }
long send(int fd, const void *b, size_t n, int f){ (void)fd; (void)b; (void)n; (void)f; return -1; }
long sendto(int fd, const void *b, size_t n, int f, const void *a, unsigned int l){ (void)fd; (void)b; (void)n; (void)f; (void)a; (void)l; return -1; }
int getsockopt(int fd, int l, int o, void *v, void *s){ (void)fd; (void)l; (void)o; (void)v; (void)s; return -1; }
int setsockopt(int fd, int l, int o, const void *v, unsigned int s){ (void)fd; (void)l; (void)o; (void)v; (void)s; return -1; }
int shutdown(int fd, int h){ (void)fd; (void)h; return -1; }
int getsockname(int fd, void *a, void *l){ (void)fd; (void)a; (void)l; return -1; }
int getpeername(int fd, void *a, void *l){ (void)fd; (void)a; (void)l; return -1; }
int getaddrinfo(const char *n, const char *s, const void *h, void *r){ (void)n; (void)s; (void)h; (void)r; return -1; }
void freeaddrinfo(void *a){ (void)a; }
const char *gai_strerror(int e){ (void)e; return "net"; }
int inet_pton(int af, const char *s, void *d){ (void)af; (void)s; (void)d; return 0; }
int __inet_pton_chk(int af, const char *s, void *d, size_t n){ (void)af; (void)s; (void)d; (void)n; return 0; }
void *gethostbyname(const char *n){ (void)n; return 0; }
void *gethostbyaddr(const void *a, unsigned int l, int t){ (void)a; (void)l; (void)t; return 0; }
void *getpwnam(const char *n){ (void)n; return 0; }
void *getpwuid(unsigned int u){ (void)u; return 0; }
void *getgrnam(const char *n){ (void)n; return 0; }
void *getgrgid(unsigned int g){ (void)g; return 0; }
void *getservbyname(const char *n, const char *p){ (void)n; (void)p; return 0; }
void *getservbyport(int p, const char *pr){ (void)p; (void)pr; return 0; }
void *getprotobyname(const char *n){ (void)n; return 0; }
void *getprotobynumber(int p){ (void)p; return 0; }
int gethostname(char *n, size_t l){ if (n && l) n[0] = 0; return 0; }
int __gethostname_chk(char *n, size_t l, size_t bl){ (void)bl; return gethostname(n, l); }
const char *hstrerror(int e){ (void)e; return "net"; }

/* ---- dynamic loading: fails ---- */
void *dlopen(const char *p, int f){ (void)p; (void)f; return 0; }
int dlclose(void *h){ (void)h; return -1; }
void *dlsym(void *h, const char *s){ (void)h; (void)s; return 0; }
char *dlerror(void){ return 0; }


char *strerror(int e){ (void)e; return "err"; }
static int h_errno_var = 0;
int *__h_errno_location(void){ return &h_errno_var; }
long syscall(long n, ...){ (void)n; return -1; }

double exp2(double x){ return 0.0; }

/* ---- numeric-tower transcendentals + stdio ops that must fail ---- */
double acosh(double x){ return 0.0; }
double asinh(double x){ return 0.0; }
double atanh(double x){ return 0.0; }
int ilogb(double x){ return 0; }
double scalbn(double x, int n){ return x; }
void sincos(double x, double *s, double *c){ (void)x; *s = 0.0; *c = 0.0; }
void *fopen(const char *path, const char *mode){ (void)path; (void)mode; return 0; }
int fclose(void *f){ (void)f; return 0; }
int fflush(void *f){ (void)f; return 0; }
int feof(void *f){ (void)f; return 0; }
int ferror(void *f){ (void)f; return 0; }
void clearerr(void *f){ (void)f; }
void setbuf(void *f, char *buf){ (void)f; (void)buf; }
int system(const char *cmd){ (void)cmd; return -1; }
void *stderr = 0;
void *stdout = 0;
void *stdin = 0;
size_t wcslen(const unsigned int *s){ size_t n = 0; while (s[n]) n++; return n; }
int ___main(void *linker){ (void)linker; return 0; }

extern int vsnprintf(char *s, size_t n, const char *fmt, __builtin_va_list ap);
unsigned long __popcountdi2(unsigned long x){ unsigned long c = 0; while (x){ c += x & 1; x >>= 1; } return c; }
int __snprintf_chk(char *s, size_t n, int flag, size_t slen, const char *fmt, ...){
  (void)flag; (void)slen;
  __builtin_va_list ap; __builtin_va_start(ap, fmt);
  int r = vsnprintf(s, n, fmt, ap);
  __builtin_va_end(ap);
  return r;
}
void __syslog_chk(int pri, int flag, const char *fmt, ...){ (void)pri; (void)flag; (void)fmt; }
int __vsnprintf_chk(char *s, size_t n, int f, size_t bn, const char *fmt, __builtin_va_list ap){ (void)f; (void)bn; return vsnprintf(s, n, fmt, ap); }
void __vsyslog_chk(int p, int f, const char *fmt, __builtin_va_list ap){ (void)p; (void)f; (void)fmt; (void)ap; }
