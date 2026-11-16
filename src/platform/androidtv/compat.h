/* Calls newer than the oldest Android TV the app runs on (API 21), given to every file of the Android TV build
   (tools/build.sh -include): each one here has a stand-in in compat_atv.c, used on every version alike. */
#ifndef SB_ATV_COMPAT_H
#define SB_ATV_COMPAT_H
#if defined(__ANDROID__) && __ANDROID_API__ < 24
struct ifaddrs;   /* (no headers here: this file goes into every source)*/
#define getifaddrs sb_getifaddrs     /* Android 7 (API 24) brought them; IPv4 interfaces are all the app reads */
#define freeifaddrs sb_freeifaddrs
int sb_getifaddrs(struct ifaddrs **out);
void sb_freeifaddrs(struct ifaddrs *list);
#endif
#endif
