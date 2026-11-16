/* Stand-ins for calls Android only has from a later version than the app's oldest (compat.h). */
#include <ifaddrs.h>
#include "platform/androidtv/compat.h"
#if defined(__ANDROID__) && __ANDROID_API__ < 24
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <net/if.h>
#include <netinet/in.h>
/* the IPv4 interfaces, from the kernel's interface list (SIOCGIFCONF), each with its flags and netmask */
typedef struct { struct ifaddrs ia; struct sockaddr_in addr, mask, brd; char name[IFNAMSIZ]; } Node;
int sb_getifaddrs(struct ifaddrs **out) {
  *out = NULL; int fd = socket(AF_INET, SOCK_DGRAM, 0); if (fd < 0) return -1;
  struct ifreq reqs[32]; struct ifconf ic; ic.ifc_len = sizeof reqs; ic.ifc_req = reqs;
  if (ioctl(fd, SIOCGIFCONF, &ic) < 0) { close(fd); return -1; }
  struct ifaddrs *head = NULL, **tail = &head; int n = ic.ifc_len / (int)sizeof(struct ifreq);
  for (int i = 0; i < n; i++) { if (reqs[i].ifr_addr.sa_family != AF_INET) continue;
    Node *x = calloc(1, sizeof *x); if (!x) break;
    memcpy(x->name, reqs[i].ifr_name, IFNAMSIZ); x->name[IFNAMSIZ - 1] = 0; memcpy(&x->addr, &reqs[i].ifr_addr, sizeof x->addr);
    struct ifreq r; memset(&r, 0, sizeof r); memcpy(r.ifr_name, x->name, IFNAMSIZ);
    if (!ioctl(fd, SIOCGIFFLAGS, &r)) x->ia.ifa_flags = (unsigned)r.ifr_flags;
    if (!ioctl(fd, SIOCGIFNETMASK, &r)) { memcpy(&x->mask, &r.ifr_netmask, sizeof x->mask); x->ia.ifa_netmask = (struct sockaddr *)&x->mask; }
    if ((x->ia.ifa_flags & IFF_BROADCAST) && !ioctl(fd, SIOCGIFBRDADDR, &r)) { memcpy(&x->brd, &r.ifr_broadaddr, sizeof x->brd); x->ia.ifa_broadaddr = (struct sockaddr *)&x->brd; }
    x->ia.ifa_name = x->name; x->ia.ifa_addr = (struct sockaddr *)&x->addr; *tail = &x->ia; tail = &x->ia.ifa_next; }
  close(fd); *out = head; return 0;
}
void sb_freeifaddrs(struct ifaddrs *list) { while (list) { struct ifaddrs *n = list->ifa_next; free(list); list = n; } }
#endif
