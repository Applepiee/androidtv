/* Android TV's own glue between the platform files and the Java side (MainActivity, VideoPlane). */
#ifndef SB_ATV_H
#define SB_ATV_H
#include <jni.h>
#define ATV_PKG "app/stremioorigin/tv"   /* the Java package (keep in step with androidtv/app/build.gradle) */
#define ATV_KEY_BASE 0x1000             /* remote keys from Java: scancode = base + Android's KEYCODE_* */
enum { ATV_MODEL = 0, ATV_RELEASE, ATV_LOCALE, ATV_FILES, ATV_CACHE, ATV_LIBDIR, ATV_CAPS, ATV_PANEL, ATV_MAC, ATV_CASTX, ATV_EXIT, ATV_MODELINES };   /* MainActivity.info() */
JNIEnv *atv_env(void);
jclass atv_class(JNIEnv *e, int which);   /* 0 MainActivity, 1 VideoPlane */
jmethodID atv_method(JNIEnv *e, int which, const char *name, const char *sig);
int atv_check(JNIEnv *e);                 /* 0 when Java threw (cleared, logged) */
void atv_jstr(JNIEnv *e, jstring s, char *out, size_t cap);
void atv_call_int(const char *name, int v);
void atv_home_rows_tick(void);
int  atv_home_link(const char *url);      /* a home-screen card's play link: 1 when it was one (homerows_atv.c) */            /* homerows_atv.c: the TV's home-screen rows, from plat_luna_pump */
#endif
