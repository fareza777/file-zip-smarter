#!/usr/bin/env bash
# Builds libhatch_core.so for Android and copies it into the app's jniLibs.
#
# Guard rail: linking against bionic's *static* libc pulls in a second scudo
# allocator that was never initialised (its preinit runs only for the main
# executable). That copy corrupts/aborts on the first free inside the library,
# which shows up as SIGFPE inside scudo when opening an archive. Every artifact
# must therefore depend on the shared libc.so stub for the target API level.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-C:/Android/Sdk}}"
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
NDK="$SDK/ndk/$NDK_VERSION"
if [ ! -d "$NDK" ]; then
  echo "NDK not found at $NDK" >&2
  exit 1
fi
HOST_TAG="windows-x86_64"
case "$(uname -s)" in
  Linux*) HOST_TAG="linux-x86_64" ;;
  Darwin*) HOST_TAG="darwin-x86_64" ;;
esac
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
SYSROOT="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/sysroot"
API=26
export PATH="$BIN:$PATH"

build() {
  local triple="$1" abi="$2" cc="$3" cxx="$4" libdir="$5"
  local env_triple="${triple//-/_}"
  local upper="${triple^^}"
  upper="${upper//-/_}"
  echo "==> $triple ($abi)"
  export "CC_${env_triple}=$cc"
  export "CXX_${env_triple}=$cxx"
  export "AR_${env_triple}=$BIN/llvm-ar.exe"
  export "CFLAGS_${env_triple}=--target=${triple}${API}"
  export "CXXFLAGS_${env_triple}=--target=${triple}${API}"
  export "CARGO_TARGET_${upper}_LINKER=$cc"
  export "CARGO_TARGET_${upper}_RUSTFLAGS=-C link-arg=--target=${triple}${API} -C link-arg=-lc++_shared"
  export "CARGO_PROFILE_RELEASE_DEBUG=false"
  local so="$ROOT/core/target/$triple/release/libhatch_core.so"
  # Force a fresh link so a stale, differently linked artifact can never be
  # packaged even if Cargo would otherwise consider the crate up to date.
  rm -f "$so"
  cargo build --manifest-path "$ROOT/core/Cargo.toml" --target "$triple" --release

  if grep -aq 'scudo' "$so"; then
    echo "error: $so embeds static bionic (scudo); refusing to package it." >&2
    echo "       Run 'cargo clean --target $triple --manifest-path core/Cargo.toml' and retry." >&2
    exit 1
  fi
  if ! "$BIN/llvm-readelf.exe" -d "$so" | grep -q 'NEEDED.*libc\.so'; then
    echo "error: $so does not depend on shared libc.so; refusing to package it." >&2
    exit 1
  fi

  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  cp "$so" "$ROOT/app/src/main/jniLibs/$abi/"
  cp "$SYSROOT/usr/lib/$libdir/libc++_shared.so" "$ROOT/app/src/main/jniLibs/$abi/"
}

case "${1:-all}" in
  arm64) build aarch64-linux-android arm64-v8a "$BIN/clang.exe" "$BIN/clang++.exe" aarch64-linux-android ;;
  x86_64) build x86_64-linux-android x86_64 "$BIN/clang.exe" "$BIN/clang++.exe" x86_64-linux-android ;;
  all)
    build aarch64-linux-android arm64-v8a "$BIN/clang.exe" "$BIN/clang++.exe" aarch64-linux-android
    build x86_64-linux-android x86_64 "$BIN/clang.exe" "$BIN/clang++.exe" x86_64-linux-android
    ;;
  *)
    echo "usage: $0 [arm64|x86_64|all]" >&2
    exit 2
    ;;
esac

echo "Done. jniLibs:"
find "$ROOT/app/src/main/jniLibs" -maxdepth 2 -type f -printf '  %p (%k KB)\n' 2>/dev/null || find "$ROOT/app/src/main/jniLibs" -maxdepth 2 -type f -exec ls -lh {} \;
