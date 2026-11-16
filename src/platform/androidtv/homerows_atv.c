/* Android TV's home screen: what the user is watching, in the TV's own rows (Java: HomeRows). Android 8 and up: the
   system's Play Next / Watch Next row (a part-watched title: continue; a show's new episode: next) and the app's own
   row, "Stremio Origin"; Android 5 to 7: the launcher's recommendations. The list is Continue Watching as Home shows
   it (lib_cw_items), sent to Java as JSON when it changes (Trakt's paused titles in it when that setting is on): a few seconds after the library settles, and at most once a
   minute while it keeps moving (a film playing saves its place every few seconds). No switch in the app: the TV's own
   settings turn each row off and on (Settings, Device Preferences, Home screen). Signed out, the list is empty:
   nothing of the account stays on the TV's home screen. */
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include "platform/androidtv/atv.h"
#include "core/app.h"
#include "core/store.h"
#include "core/stremio.h"
#include "core/best.h"
#include "core/traktwatch.h"
#include "core/keys.h"
#include "screens/player.h"
#include "screens/btrailer.h"
#include "cJSON.h"

#define HR_MAX 20   /* the app's row; Java puts the first 10 in Watch Next */

static double mtime_ms(const cJSON *v) { /* _mtime: epoch ms or ISO text (as stremio.c reads it) */
  if (cJSON_IsNumber(v)) return v->valuedouble;
  if (cJSON_IsString(v) && v->valuestring) { struct tm tm; memset(&tm, 0, sizeof tm); int ms = 0;
    if (sscanf(v->valuestring, "%d-%d-%dT%d:%d:%d.%d", &tm.tm_year, &tm.tm_mon, &tm.tm_mday, &tm.tm_hour, &tm.tm_min, &tm.tm_sec, &ms) >= 6) {
      tm.tm_year -= 1900; tm.tm_mon -= 1; return (double)timegm(&tm) * 1000 + ms; } }
  return 0;
}
static unsigned long hash_str(const char *s) { unsigned long h = 5381; while (*s) h = h * 33 ^ (unsigned char)*s++; return h; }

static char *build(void) {
  cJSON *j = cJSON_CreateObject(), *arr = cJSON_AddArrayToObject(j, "items");
  { ItemList l = { 0 }; int n = lib_cw_items(&l);
    for (int i = 0; i < n && i < HR_MAX; i++) { const Item *it = &l.items[i]; if (!it->id[0]) continue;
      cJSON *o = cJSON_CreateObject(); char buf[600];
      cJSON_AddStringToObject(o, "id", it->id); cJSON_AddStringToObject(o, "type", it->type); cJSON_AddStringToObject(o, "name", it->name);
      cJSON_AddStringToObject(o, "vid", it->videoId[0] ? it->videoId : it->id);
      cJSON_AddStringToObject(o, "poster", sb_poster_of(it, buf, sizeof buf)); cJSON_AddStringToObject(o, "bg", sb_bg_mid(it, buf, sizeof buf));
      /* a show's episode from its id (<show>:<season>:<episode>, or <show>:<episode>) */
      size_t il = strlen(it->id); int s = -1, e = -1;
      if (!strcmp(it->type, "series") && !strncmp(it->videoId, it->id, il) && it->videoId[il] == ':') { int a, b; int k = sscanf(it->videoId + il + 1, "%d:%d", &a, &b); if (k == 2) { s = a; e = b; } else if (k == 1) e = a; }
      if (s >= 0) cJSON_AddNumberToObject(o, "s", s);
      if (e >= 0) cJSON_AddNumberToObject(o, "e", e);
      /* where it stands: the length from what is left (minutes) and how far in (0..1) */
      double dur = 0, pos = 0; if (it->progress > 0.001f && it->progress < 1 && it->left > 0) { dur = it->left * 60000.0 / (1 - it->progress); pos = dur * it->progress; }
      cJSON_AddNumberToObject(o, "pos", (double)(long long)pos); cJSON_AddNumberToObject(o, "dur", (double)(long long)dur);
      cJSON_AddNumberToObject(o, "next", it->newEp && !(pos > 0) ? 1 : 0);
      cJSON_AddNumberToObject(o, "cont", it->progress > 0.001f && it->progress < 1 && !it->newEp ? 1 : 0); /* part-watched, its length known or not (Trakt's pause gives only how far in): continue, not next */
      const cJSON *lit = lib_get(it->id); double t = lit ? mtime_ms(cJSON_GetObjectItemCaseSensitive(lit, "_mtime")) : 0;
      { const TwPlay *tp = tw_play_of(it->id); if (tp && (double)tp->at * 1000 > t) t = (double)tp->at * 1000; } /* (paused on Trakt since: its time, so the TV's row keeps the app's order) */
      cJSON_AddNumberToObject(o, "t", (double)(long long)t);
      cJSON_AddItemToArray(arr, o); }
    itemlist_clear(&l); }
  char *s = cJSON_PrintUnformatted(j); cJSON_Delete(j); return s;
}

static void send_java(const char *json) {
  JNIEnv *e = atv_env(); jmethodID m = atv_method(e, 0, "homeRows", "(Ljava/lang/String;)V"); if (!m) return;
  jstring s = (*e)->NewStringUTF(e, json); (*e)->CallStaticVoidMethod(e, atv_class(e, 0), m, s); atv_check(e); (*e)->DeleteLocalRef(e, s);
}

/* A card pressed on the TV's home screen (stremioorigin://play/<type>/<id>/<video>, MainActivity hands it over as
   stremio://play/...): what the app's own Continue Watching does, and with "Play the best stream automatically" on,
   what the title page's Play does: the stream it was playing on this TV, at its place; else the best stream, straight
   into the loader (at the saved place, or a new episode from its start); best stream off and nothing kept: the title
   page with that episode's streams. The app just opened by it: once it is past its start (signed in, its first screen
   up), anything over the page (a card, a sheet) closed first; no background trailer meanwhile. */
static struct { char type[16], id[64], vid[96]; double at; int on; } pend;
int atv_home_link(const char *url) {
  if (strncmp(url, "stremio://play/", 15)) return 0;
  char t[300]; snprintf(t, sizeof t, "%s", url + 15); char *ty = t, *id = strchr(t, '/'), *vid = NULL;
  if (id) { *id++ = 0; vid = strchr(id, '/'); if (vid) *vid++ = 0; }
  if (!id || !*ty || !*id) return 1;
  memset(&pend, 0, sizeof pend); snprintf(pend.type, sizeof pend.type, "%s", ty); snprintf(pend.id, sizeof pend.id, "%s", id); snprintf(pend.vid, sizeof pend.vid, "%s", vid && *vid ? vid : id);
  pend.at = app_now(); pend.on = 1; btr_cast_hold(20);
  fprintf(stderr, "[home] a card from the TV's home screen: %s %s\n", pend.type, pend.vid); return 1;
}
static void home_play_tick(void) {
  if (!pend.on) return; double now = app_now();
  if (now - pend.at > 60) { pend.on = 0; fprintf(stderr, "[home] the card's title not played: the app did not get past its start\n"); return; }
  if (SCR == SCR_LOGIN || !sb_signed_in() || now < 3 || now - pend.at < 0.3) return; /* (the start's own screens first) */
  pend.on = 0; if (app_overlay && app_overlay->key) app_overlay->key(K_BACK); app_overlay = NULL;
  Stream st;
  if (sb_resume_stream(pend.id, pend.vid, &st)) { fprintf(stderr, "[home] the card: the stream it was playing\n"); app_play(&st, pend.type, pend.id, pend.vid, 0); return; }
  if (best_on()) { fprintf(stderr, "[home] the card: the best stream\n"); player_choose_best(pend.type, pend.id, pend.vid); return; }
  char u[300]; snprintf(u, sizeof u, "stremio:///detail/%s/%s/%s", pend.type, pend.id, pend.vid); fprintf(stderr, "[home] the card: its streams (best stream off)\n"); app_open_external(u, NULL);
}

/* once a frame (plat_luna_pump): cheap until the library or the switch moves */
void atv_home_rows_tick(void) {
  home_play_tick();
  static unsigned sentVer, lastVer; static int have; static double changedAt, sentAt, nextLook; static unsigned long sentHash;
  double now = app_now(); if (now < nextLook) return; nextLook = now + 1.0;
  if (now < 8) return; /* (the first list after the start's own work: the library is read by then) */
  unsigned v = lib_version() * 2654435761u ^ tw_ver(); /* (the library, or Trakt's Continue Watching, moved) */
  if (v != lastVer) { lastVer = v; changedAt = now; }
  int due = !have; /* the first at once */
  if (!due && v != sentVer) due = now - changedAt >= 4 || now - sentAt >= 60; /* the library moved: once it settles, at most a minute apart while it keeps moving */
  if (!due) return;
  sentVer = v; have = 1;
  char *json = build(); if (!json) return;
  unsigned long h = hash_str(json);
  if (h != sentHash) { sentHash = h; sentAt = now; send_java(json);
    cJSON *p = cJSON_Parse(json); fprintf(stderr, "[home] the TV's home screen: %d titles\n", p ? cJSON_GetArraySize(cJSON_GetObjectItemCaseSensitive(p, "items")) : 0); cJSON_Delete(p); }
  cJSON_free(json);
}
