/* shim.h — declarations for libc/POSIX names the generated Gambit C calls
   without including the system header itself. Force-included via -include
   shim.h for generated files only (our stubs define the same names). */
#define ___GAMBITDIR ""
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include <stdio.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/select.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/time.h>
#include <sys/uio.h>
#include <termios.h>
#include <poll.h>
#include <dirent.h>
