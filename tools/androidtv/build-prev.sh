#!/bin/bash
# Android TV's seek-preview decoder: FFmpeg with only the H.264 and HEVC decoders (no demuxers, muxers, parsers, filters
# or scaler: the app reads the keyframe itself and hands its bytes over), and the shared preview call (tools/dts/sbdts.c
# built with SBD_PREVIEW), as one small library per ABI: libsbprev.so, exporting sbd_prev_get only. The app finds it by
# name (core/seekprev.c, prevspike.c); without it, no previews and the Settings row stays hidden.
# The same build as the phone's (tools/phone/build-prev.sh on the phone branch), from the FFmpeg source already here:
# nothing is downloaded. -O2, not --enable-small: less CPU a picture on a box's weak cores.
# Packed by default as LG's build carries its decoders (2026-11-11); SB_PREVIEW_LIB=0 to apk.sh leaves it out.
#   tools/androidtv/build-prev.sh [arm64-v8a|armeabi-v7a]...   (both by default)
# LGPL-2.1+ as the rest of FFmpeg here (no --enable-gpl, no nonfree).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ORIGIN_BUILD="${ORIGIN_BUILD:-$HOME/origin-build}"
OBREAL="$(cd "$ORIGIN_BUILD" 2>/dev/null && pwd -P || echo "$ORIGIN_BUILD")"
W="$OBREAL/ffmpeg"; VERSION=7.1.1; SRC="$W/ffmpeg-$VERSION"
NDK="$OBREAL/android/sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/darwin-x86_64/bin"
[ -f "$SRC/configure" ] || { echo "no FFmpeg source at $SRC"; exit 1; }
ABIS=("$@"); [ ${#ABIS[@]} -eq 0 ] && ABIS=(arm64-v8a armeabi-v7a)
PM="-ffile-prefix-map=$HOME=/src -ffile-prefix-map=$ORIGIN_BUILD=/build -ffile-prefix-map=$OBREAL=/build -ffile-prefix-map=$ROOT=/app"   # (no build-machine paths; the last map that fits is used)
for ABI in "${ABIS[@]}"; do
  case "$ABI" in
    arm64-v8a) TC="$W/tc-atv-arm64"; ARCH=aarch64 ;;
    armeabi-v7a) TC="$W/tc-atv-armv7"; ARCH=arm ;;
    *) echo "unknown ABI $ABI"; exit 1 ;;
  esac
  [ -x "$TC/cc" ] || { echo "no $TC/cc: run tools/build-ffmpeg.sh atv-arm64 / atv-armv7 first"; exit 1; }
  B="$W/build-atv-prev-$ABI"; P="$W/prefix-atv-prev-$ABI"; mkdir -p "$B"
  XC="-O2 -fPIC -ffunction-sections -fdata-sections $PM"
  if [ ! -f "$P/lib/libavcodec.a" ]; then
    (cd "$B" && "$SRC/configure" --prefix="$P" --disable-autodetect --disable-shared --enable-static --enable-pic \
      --disable-programs --disable-doc --disable-debug --disable-network --disable-avdevice --disable-avfilter --disable-swscale --disable-postproc \
      --enable-avcodec --enable-avformat --enable-avutil --enable-swresample --disable-iconv --disable-zlib \
      --disable-everything --enable-decoder=h264,hevc \
      --enable-cross-compile --arch=$ARCH --target-os=android --cc="$TC/cc" --ar="$NDK/llvm-ar" --ranlib="$NDK/llvm-ranlib" --nm="$NDK/llvm-nm" --strip="$NDK/llvm-strip" \
      --extra-cflags="$XC" >/dev/null && make -j6 >/dev/null && make install >/dev/null)
  fi
  OUT="$ROOT/build/atv-prev/$ABI"; mkdir -p "$OUT"
  echo '{ global: sbd_prev_get; local: *; };' > "$OUT/exports.map"
  "$TC/cc" -std=c11 -O2 -fPIC -shared -fvisibility=hidden -ffunction-sections -fdata-sections -D_GNU_SOURCE -DSBD_PREVIEW -I"$P/include" -I"$ROOT/src" \
    $PM "$ROOT/tools/dts/sbdts.c" \
    -L"$P/lib" -lavformat -lavcodec -lswresample -lavutil -lm \
    -Wl,--gc-sections -Wl,--version-script="$OUT/exports.map" -Wl,-z,max-page-size=16384 -Wl,--build-id=none -o "$OUT/libsbprev.so"
  "$NDK/llvm-strip" --strip-all "$OUT/libsbprev.so"
  echo "$ABI: $(stat -f %z "$OUT/libsbprev.so") bytes -> build/atv-prev/$ABI/libsbprev.so"
done
