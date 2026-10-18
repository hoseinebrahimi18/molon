#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="$ROOT_DIR/Android-Port/vendor/SoftEther-Android-Module"
OPENSSL_DIR="$MODULE_DIR/src/main/cpp/openssl"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
NDK_VERSION="${ANDROID_NDK_VERSION:-27.2.12479018}"

if [[ -z "$SDK_ROOT" ]]; then
  echo "ERROR: ANDROID_HOME or ANDROID_SDK_ROOT must point to the Android SDK." >&2
  exit 1
fi

NDK_ROOT="$SDK_ROOT/ndk/$NDK_VERSION"
if [[ ! -d "$NDK_ROOT" ]]; then
  echo "ERROR: Android NDK not found at $NDK_ROOT" >&2
  exit 1
fi
if [[ ! -f "$OPENSSL_DIR/Configure" ]]; then
  echo "ERROR: OpenSSL source is missing at $OPENSSL_DIR; run bootstrap-softether.sh first." >&2
  exit 1
fi

export ANDROID_NDK_ROOT="$NDK_ROOT"
export PATH="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)"
JOBS="$(( JOBS > 4 ? 4 : JOBS ))"

build_one() {
  local target="$1"
  local abi="$2"
  local prefix="$MODULE_DIR/src/main/cpp/jniLibs/$abi"
  echo "=== Building OpenSSL for $abi ($target) ==="

  cd "$OPENSSL_DIR"
  make distclean >/dev/null 2>&1 || true
  ./Configure "$target" -D__ANDROID_API__=26 no-shared no-tests no-apps --prefix="$prefix"
  make -j"$JOBS"
  make install_sw

  mkdir -p "$prefix"
  cp "$prefix/lib/libssl.a" "$prefix/libssl.a"
  cp "$prefix/lib/libcrypto.a" "$prefix/libcrypto.a"
  test -s "$prefix/include/openssl/configuration.h"
  test -s "$prefix/libssl.a"
  test -s "$prefix/libcrypto.a"
  echo "OpenSSL ready for $abi"
}

build_one android-arm64 arm64-v8a
build_one android-arm armeabi-v7a
build_one android-x86_64 x86_64

echo "All Android OpenSSL static libraries and generated headers are ready."
