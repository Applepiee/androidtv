/* Android TV's engine library: built against Android 7 (API 24) so libtorrent lists the network with getifaddrs (the
   netlink way it takes for older targets is refused to apps from Android 11 on, and then no connection is ever made),
   while it still loads on Android 5 and 6, which have no getifaddrs: these stand in for it there (IPv4, from the
   kernel's interface list) and hand over to the system's own where it has it. Linked into libsbengine.so, hidden. */
#include <dlfcn.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>
#define HIDDEN __attribute__((visibility("hidden")))
typedef int (*GetFn)(struct ifaddrs **); typedef void (*FreeFn)(struct ifaddrs *);
static GetFn sysGet; static FreeFn sysFree; static int looked;
static void look(void) { if (looked) return; looked = 1; void *c = dlopen("libc.so", RTLD_NOW); if (!c) return;
  sysGet = (GetFn)dlsym(c, "getifaddrs"); sysFree = (FreeFn)dlsym(c, "freeifaddrs"); if (!sysGet || !sysFree) sysGet = NULL, sysFree = NULL; }
typedef struct { struct ifaddrs ia; struct sockaddr_in addr, mask, brd; char name[IFNAMSIZ]; unsigned magic; } Node;
#define OURS 0x5b0e1f
HIDDEN int getifaddrs(struct ifaddrs **out) {
  look(); if (sysGet) return sysGet(out);
  *out = NULL; int fd = socket(AF_INET, SOCK_DGRAM, 0); if (fd < 0) return -1;
  struct ifreq reqs[32]; struct ifconf ic; ic.ifc_len = sizeof reqs; ic.ifc_req = reqs;
  if (ioctl(fd, SIOCGIFCONF, &ic) < 0) { close(fd); return -1; }
  struct ifaddrs *head = NULL, **tail = &head; int n = ic.ifc_len / (int)sizeof(struct ifreq);
  for (int i = 0; i < n; i++) { if (reqs[i].ifr_addr.sa_family != AF_INET) continue;
    Node *x = calloc(1, sizeof *x); if (!x) break; x->magic = OURS;
    memcpy(x->name, reqs[i].ifr_name, IFNAMSIZ); x->name[IFNAMSIZ - 1] = 0; memcpy(&x->addr, &reqs[i].ifr_addr, sizeof x->addr);
    struct ifreq r; memset(&r, 0, sizeof r); memcpy(r.ifr_name, x->name, IFNAMSIZ);
    if (!ioctl(fd, SIOCGIFFLAGS, &r)) x->ia.ifa_flags = (unsigned)r.ifr_flags;
    if (!ioctl(fd, SIOCGIFNETMASK, &r)) { memcpy(&x->mask, &r.ifr_netmask, sizeof x->mask); x->ia.ifa_netmask = (struct sockaddr *)&x->mask; }
    if ((x->ia.ifa_flags & IFF_BROADCAST) && !ioctl(fd, SIOCGIFBRDADDR, &r)) { memcpy(&x->brd, &r.ifr_broadaddr, sizeof x->brd); x->ia.ifa_broadaddr = (struct sockaddr *)&x->brd; }
    x->ia.ifa_name = x->name; x->ia.ifa_addr = (struct sockaddr *)&x->addr; *tail = &x->ia; tail = &x->ia.ifa_next; }
  close(fd); *out = head; return 0;
}
HIDDEN void freeifaddrs(struct ifaddrs *list) {
  look(); if (sysFree) { sysFree(list); return; }
  while (list) { struct ifaddrs *n = list->ifa_next; free(list); list = n; }
}

/* Android 5 and 6: calls the API 24 build reaches for that their libc does not have (the fortified write, the fork
   handlers; on 32-bit also the stream the C++ library writes errors to), each mapped to what it is */
#include <stdio.h>
#include <pthread.h>
HIDDEN ssize_t __write_chk(int fd, const void *buf, size_t count, size_t bufSize) { if (count > bufSize) abort(); return write(fd, buf, count); }
HIDDEN ssize_t __pwrite_chk(int fd, const void *buf, size_t count, off_t off, size_t bufSize) { if (count > bufSize) abort(); return pwrite(fd, buf, count, off); }
HIDDEN ssize_t __pread_chk(int fd, void *buf, size_t count, off_t off, size_t bufSize) { if (count > bufSize) abort(); return pread(fd, buf, count, off); }
HIDDEN int __register_atfork(void (*prepare)(void), void (*parent)(void), void (*child)(void), void *dso) { (void)dso; return pthread_atfork(prepare, parent, child); }
#if defined(__arm__)
HIDDEN FILE *stderr;
__attribute__((constructor)) static void find_stderr(void) {   /* the system's own where it has it; else the third of its old stream table (84 bytes each on 32-bit) */
  void *c = dlopen("libc.so", RTLD_NOW); if (!c) return; FILE **s = (FILE **)dlsym(c, "stderr");
  if (s) { stderr = *s; return; } char *sf = (char *)dlsym(c, "__sF"); if (sf) stderr = (FILE *)(sf + 2 * 84); }
#endif
