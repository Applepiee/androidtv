#!/usr/bin/env bash
# The formatting is rough and I'm not going to bother. Pull requests that fix it are welcome; complaints without one help neither me nor the end user.
# Builds the Stremio Origin Android TV APK into dist/: ONE APK holding both library sets (64-bit arm64-v8a and 32-bit
# armeabi-v7a); Android installs the one the device runs.
# Usage: tools/androidtv/apk.sh [debug|release]   (after tools/build.sh atv-arm64 AND tools/build.sh atv-armv7: one target
# per run of build.sh; the APK carries both, and stops when either is older than the code)
set -euo pipefail

MODE="${1:-debug}"
case "$MODE" in
  debug|release) ;;
  *) echo "usage: $0 [debug|release]" >&2; exit 2 ;;
esac
TASK="assemble$(tr '[:lower:]' '[:upper:]' <<< "${MODE:0:1}")${MODE:1}"

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
PHONE="$ROOT/androidtv"
TOOLS="${ORIGIN_BUILD:-$HOME/origin-build}/android"
KEYS="$TOOLS/keys"
PROPS="$KEYS/tv.properties"

export ANDROID_HOME="$TOOLS/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE="$TOOLS/gradle-8.11.1/bin/gradle"

[ -d "$ANDROID_HOME" ] || { echo "error: Android SDK not found at $ANDROID_HOME" >&2; exit 1; }
[ -x "$GRADLE" ] || { echo "error: Gradle not found at $GRADLE" >&2; exit 1; }

# Gradle 8.11 / AGP 8.7 do not run on JDK 24+; pick a JDK 17-23 by real version.
jdk_major() {
  "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1
}
JDK=""
for c in \
  "$(brew --prefix openjdk 2>/dev/null || true)/libexec/openjdk.jdk/Contents/Home" \
  "$(brew --prefix openjdk@17 2>/dev/null || true)/libexec/openjdk.jdk/Contents/Home" \
  "$(/usr/libexec/java_home -v 23 2>/dev/null || true)" \
  "$(/usr/libexec/java_home -v 17 2>/dev/null || true)"; do
  [ -n "$c" ] && [ -x "$c/bin/java" ] || continue
  major="$(jdk_major "$c")"
  if [ "$major" -ge 17 ] && [ "$major" -le 23 ]; then JDK="$c"; break; fi
done
[ -n "$JDK" ] || { echo "error: no JDK 17-23 found (brew install openjdk@17)" >&2; exit 1; }
export JAVA_HOME="$JDK"
echo "JDK: $JAVA_HOME"

# Local SDK location for Gradle (never committed).
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$PHONE/local.properties"

# Native libraries: one set per ABI, extracted by Gradle (useLegacyPackaging).
JNI="$PHONE/app/src/main/jniLibs"
for ABI in arm64-v8a armeabi-v7a; do
  mkdir -p "$JNI/$ABI"
  rm -f "$JNI/$ABI"/*.so
  B="$ROOT/build/atv-$ABI"
  [ -f "$B/libmain.so" ] || { echo "error: $ABI not built ($B/libmain.so): run tools/build.sh atv-arm64 and tools/build.sh atv-armv7 first, the APK carries both" >&2; exit 1; }
  NEWER=$(find "$ROOT/src" "$ROOT/tools/build.sh" -newer "$B/libmain.so" \( -name '*.c' -o -name '*.h' -o -name '*.m' -o -name build.sh \) 2>/dev/null | head -1)
  [ -z "$NEWER" ] || { echo "error: $ABI's libmain.so is older than ${NEWER#$ROOT/}: run tools/build.sh atv-$([ $ABI = arm64-v8a ] && echo arm64 || echo armv7) (build.sh builds one target per run)" >&2; exit 1; }
  for f in "$B/deps/lib/libSDL2.so" "$B/deps/lib/libcurl.so" "$B/libmain.so"; do
    if [ -f "$f" ]; then
      cp "$f" "$JNI/$ABI/"
    else
      echo "warning: missing $f" >&2
    fi
  done
  # the torrent engine (tools/build-engine.sh android-arm64 / android-armv7): optional, torrents need it
  if [ -f "$B/libsbengine.so" ]; then cp "$B/libsbengine.so" "$JNI/$ABI/"; else echo "note: no $B/libsbengine.so (torrents will not play)" >&2; fi
  # the converter (tools/build-ffmpeg.sh atv-arm64 / atv-armv7): Dolby Vision profile 7 as 8.1, the local HLS conversion
  if [ -f "$B/libsbdts.so" ]; then cp "$B/libsbdts.so" "$JNI/$ABI/"; else echo "note: no $B/libsbdts.so (no Dolby Vision conversion)" >&2; fi
  # the seek-preview decoder (tools/androidtv/build-prev.sh): packed as LG's build carries its decoders (decided 2026-11-11);
  # SB_PREVIEW_LIB=0 leaves it out (no previews, their setting hidden), as LG's packaging without SBD_PREVIEW
  rm -f "$JNI/$ABI/libsbprev.so"
  if [ "${SB_PREVIEW_LIB:-1}" = 1 ]; then PL="$ROOT/build/atv-prev/$ABI/libsbprev.so"; if [ -f "$PL" ]; then cp "$PL" "$JNI/$ABI/"; else echo "note: no $PL (run tools/androidtv/build-prev.sh)" >&2; fi; fi
done

# Every library loads on Android 5 (API 21/22): nothing it needs may be missing from that system's own libraries
# (kept in the private build folder, pulled once from an Android 5.1 image: android/api22/lib, lib64)
OLD="$TOOLS/api22"; NM="$ANDROID_HOME/ndk/27.2.12479018/toolchains/llvm/prebuilt/darwin-x86_64/bin"
if [ -d "$OLD" ]; then
  for ABI in arm64-v8a armeabi-v7a; do
    sys="$OLD/$([ $ABI = arm64-v8a ] && echo lib64 || echo lib)"
    ls "$sys"/*.so >/dev/null 2>&1 || sys="$ANDROID_HOME/ndk/27.2.12479018/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/$([ $ABI = arm64-v8a ] && echo aarch64-linux-android || echo arm-linux-androideabi)/21"   # (no system copy: the NDK's own Android 5.0 (API 21) libraries, what that system offers)
    ls "$sys"/*.so >/dev/null 2>&1 || { echo "error: no Android 5 reference for $ABI" >&2; exit 1; }
    # (|| true below: the NDK keeps a text file named libc++.so beside its libraries)
    defs=$(mktemp); for f in "$sys"/*.so "$JNI/$ABI"/*.so; do "$NM/llvm-nm" -D --defined-only --format=just-symbols "$f" 2>/dev/null || true; done | sed 's/@.*//' | sort -u > "$defs"
    for f in "$JNI/$ABI"/*.so; do
      miss=$("$NM/llvm-readelf" --dyn-syms "$f" | awk '$7=="UND" && $5!="WEAK" {print $8}' | sed 's/@.*//' | grep -v '^$' | sort -u | comm -23 - "$defs" | tr '\n' ' ' || true)
      [ -z "$miss" ] || { echo "error: $(basename "$f") ($ABI) needs what Android 5 lacks: $miss" >&2; rm -f "$defs"; exit 1; }
    done; rm -f "$defs"
  done
fi

# Release keystore, created once.
if [ "$MODE" = release ] && [ ! -f "$PROPS" ]; then
  mkdir -p "$KEYS"
  STORE="$KEYS/tv.jks"
  PASS="$(openssl rand -hex 12)"   # 24 characters
  "$JDK/bin/keytool" -genkeypair -keystore "$STORE" -storepass "$PASS" -keypass "$PASS" \
    -alias tv -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Stremio Origin"
  ( umask 077
    printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=tv\nkeyPassword=%s\n' \
      "$STORE" "$PASS" "$PASS" > "$PROPS" )
  chmod 600 "$PROPS"
  echo "created keystore: $STORE"
fi

"$GRADLE" -p "$PHONE" "$TASK" --console=plain

VERSION="${SB_VERSION:-$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$ROOT/pkg/appinfo.json")}"   # (the TV app's version: one train)
OUTDIR="$PHONE/app/build/outputs/apk/$MODE"
GITREV=$(git -C "$ROOT" rev-parse --short=10 HEAD 2>/dev/null || echo unknown)$(git -C "$ROOT" diff --quiet HEAD -- src 2>/dev/null || echo +)
for ABI in arm64-v8a armeabi-v7a; do D="$ROOT/dist/dbg/$GITREV/$ABI"; mkdir -p "$D"; for f in libmain.dbg.so libsbengine.dbg.so; do [ -f "$ROOT/build/atv-$ABI/$f" ] && cp "$ROOT/build/atv-$ABI/$f" "$D/"; done; done   # (a crash's addresses named from these: getlogs.js)
SRC=""; for f in "$OUTDIR/app-$MODE.apk" "$OUTDIR/app-$MODE-unsigned.apk"; do [ -f "$f" ] && { SRC="$f"; break; }; done
[ -n "$SRC" ] || { echo "error: no APK found in $OUTDIR" >&2; exit 1; }
# both library sets, the same files in each (an APK missing one would install and fail on half of the devices)
L64=$(unzip -Z1 "$SRC" | sed -n 's#^lib/arm64-v8a/##p' | sort | tr '\n' ' '); L32=$(unzip -Z1 "$SRC" | sed -n 's#^lib/armeabi-v7a/##p' | sort | tr '\n' ' ')
[ -n "$L64" ] && [ "$L64" = "$L32" ] || { echo "error: the APK's library sets differ: arm64-v8a [$L64] armeabi-v7a [$L32]" >&2; exit 1; }
[ -z "$(unzip -Z1 "$SRC" | grep '^lib/' | grep -vE '^lib/(arm64-v8a|armeabi-v7a)/')" ] || { echo "error: the APK carries another processor's libraries" >&2; exit 1; }
mkdir -p "$ROOT/dist"; rm -f "$ROOT/dist/stremio-origin-androidtv-"*"-$MODE.apk"
DEST="$ROOT/dist/stremio-origin-androidtv-$VERSION-$MODE.apk"; cp "$SRC" "$DEST"
echo "APK: $DEST ($(ls -lh "$DEST" | awk '{print $5}')), both processors: $L64"
# a release: the files as published, the versioned one (the in-app update reads its name and checksum) and the same file
# under a name that never changes (releases/latest/download/stremio-origin-androidtv.apk: the Downloader code's address)
if [ "$MODE" = release ]; then P="$ROOT/dist/universal-$VERSION"; mkdir -p "$P"; cp "$DEST" "$P/stremio-origin-androidtv-$VERSION.apk"; cp "$DEST" "$P/stremio-origin-androidtv.apk"; echo "to publish: $P/stremio-origin-androidtv-$VERSION.apk and $P/stremio-origin-androidtv.apk (the same file)"; fi
