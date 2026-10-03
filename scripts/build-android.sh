#!/usr/bin/env bash
# 交叉编译 Rust Agent Core → android/app/src/main/jniLibs/（AGENTS.md R5/R6/R8）。
#
# 不依赖 cargo-ndk：直接用 NDK 的 clang 作为 linker / CC / AR，
# 避免 cargo install 在本类文件系统上产出悬空软链的问题。
#
# 用法：ANDROID_NDK_HOME=/path/to/ndk bash scripts/build-android.sh
set -euo pipefail

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" ]]; then
  echo "错误：需设置 ANDROID_NDK_HOME（或 ANDROID_NDK_ROOT）指向 NDK 目录" >&2
  exit 1
fi

HOST_TAG="linux-x86_64"
case "$(uname -s)" in
  Darwin)
    HOST_TAG="darwin-$(uname -m)"
    if [[ ! -d "$NDK/toolchains/llvm/prebuilt/$HOST_TAG" ]]; then
      # 老 NDK 仅提供 darwin-x86_64（Apple 芯片经 Rosetta 运行）
      HOST_TAG="darwin-x86_64"
    fi
    ;;
esac
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
API="${ANDROID_API_LEVEL:-24}"
OUT="android/app/src/main/jniLibs"

build() {
  local target="$1" abi="$2" cc="$3"
  local upper
  upper="$(echo "$target" | tr 'a-z-' 'A-Z_')"
  echo "== $abi ($target) =="
  env "CC_${target}=$BIN/$cc" \
      "AR_${target}=$BIN/llvm-ar" \
      "CARGO_TARGET_${upper}_LINKER=$BIN/$cc" \
      cargo build -p yaya-core-jni --target "$target" --release
  mkdir -p "$OUT/$abi"
  cp "target/$target/release/libyaya_core_jni.so" "$OUT/$abi/"
  echo "  -> $OUT/$abi/libyaya_core_jni.so"
}

build aarch64-linux-android   arm64-v8a   "aarch64-linux-android${API}-clang"
build armv7-linux-androideabi armeabi-v7a "armv7a-linux-androideabi${API}-clang"
build x86_64-linux-android    x86_64      "x86_64-linux-android${API}-clang"

echo "完成。"