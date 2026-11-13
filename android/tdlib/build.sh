#!/usr/bin/env bash
# Builds TDLib for TG Flip from source and copies it here (libs/, java/; both git-ignored).
# Run inside WSL (Debian) from this folder:  bash build.sh
# Needs once: sudo apt install git cmake gperf php-cli perl default-jdk-headless make g++ unzip zip curl
# The first run downloads the TDLib source (~/td), the Android SDK + NDK 23.2 and OpenSSL 1.1.1w, and
# takes about half an hour; later runs only rebuild what changed.
# Choices: armeabi-v7a only (the Kyocera is 32-bit ARM), Java interface, Android 7.0 (API 24, the
# app's minSdk) and up, OpenSSL and the C++ library linked in statically (one .so file).
set -e
TDLIB_COMMIT=c8828d50f5eac2fc7089ce1089d7ce9095c61b5e  # TDLib 1.8.70, 2026-11-09
HERE="$(cd "$(dirname "$0")" && pwd)"

[ -d ~/td ] || git clone https://github.com/tdlib/td.git ~/td
cd ~/td
git fetch -q origin
git checkout -q "$TDLIB_COMMIT"
cd example/android
[ -d SDK ] || ./fetch-sdk.sh
if [ ! -d third-party/openssl ]; then
  sed 's/for ABI in arm64-v8a armeabi-v7a x86_64 x86 ; do/for ABI in armeabi-v7a ; do/; s/make -j4 -s/make -j"$(nproc)" -s/' \
    build-openssl.sh > build-openssl-v7a.sh
  chmod +x build-openssl-v7a.sh
  ./build-openssl-v7a.sh
fi
./build-tdlib.sh '' '' '' c++_static Java 24 armeabi-v7a

rm -rf "$HERE/libs" "$HERE/java"
cp -r tdlib/libs "$HERE/libs"
cp -r tdlib/java "$HERE/java"
echo "TDLib $TDLIB_COMMIT copied into $HERE"
ls -la "$HERE/libs/armeabi-v7a"
