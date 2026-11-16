Third-party code in this app and its licences:
- libsbengine.so: the torrent engine github.com/NuvioMedia/nuvio-engine (GPL-3.0, torrent-engine-GPL-3.0.txt), patched (tools/engine/engine.patch in the source),
  on libtorrent 2.0.12 (BSD, libtorrent-BSD.txt) and Boost 1.86 (Boost Software License 1.0, boost-BSL-1.0.txt).
- libsbdts.so: the sound converter and the seek previews' H.264 / HEVC picture decoding (tools/dts/sbdts.c) on FFmpeg 7.1.1 (LGPL 2.1 or later, ffmpeg-LGPL-2.1.txt), unmodified, built
  as described in ffmpeg-SOURCE.txt (tools/build-ffmpeg.sh); a shared library of its own, so it can be rebuilt and replaced.
- SHA-256 and AES (third_party/crypto): Brad Conte's crypto-algorithms, public domain (crypto-algorithms-public-domain.txt).
- P-256 key exchange (third_party/crypto/uecc): micro-ecc by Kenneth MacKay, BSD-2-Clause (micro-ecc-BSD-2-Clause.txt).
- FreeType, HarfBuzz, FriBidi, libass (ISC, 0.17.5), cJSON, stb, qrcodegen, libwebp, SDL2: their licences ship with their sources in third_party/.
Android TV app (the APK) also ships:
- AndroidX Media3 / ExoPlayer (Apache-2.0), the video player.
- Jellyfin's Media3 FFmpeg audio decoder (org.jellyfin.media3:media3-ffmpeg-decoder), built on FFmpeg (LGPL 2.1 or later,
  ffmpeg-LGPL-2.1.txt), unmodified.
- SDL2 (zlib), curl (curl licence) on Mbed TLS (Apache-2.0), built from their releases by tools/androidtv/build-deps.sh.
- libsbprev.so: seek previews, FFmpeg 7.1.1 with only its H.264 and HEVC decoders (LGPL 2.1 or later, ffmpeg-LGPL-2.1.txt),
  unmodified, built by tools/androidtv/build-prev.sh; a shared library of its own, so it can be rebuilt and replaced.
The app itself is GPL-3.0.
