#!/usr/bin/env bash
# 交叉编译 Rust Agent Core → android/app/src/main/jniLibs/（AGENTS.md R5/R6/R8）。
#
# 双模式：
#  1. 默认：NDK 自带 clang（x86_64/darwin 主机，CI 用）—— 直连 $NDK/bin/*-clang。
#  2. USE_SYSTEM_CLANG=1 或 aarch64 主机自动：系统 clang + NDK sysroot（纯数据）。
#     aarch64 容器里 NDK 的 x86_64 工具链二进制无法运行，但 sysroot 是跨架构数据；
#     clang --target=aarch64-linux-android --sysroot=<ndk sysroot> 即 NDK 官方同款
#     编译路径（已验证，见记忆 aarch64-android-cross-clang）。
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
      HOST_TAG="darwin-x86_64"
    fi
    ;;
esac
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
SYSROOT="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/sysroot"
API="${ANDROID_API_LEVEL:-24}"
OUT="android/app/src/main/jniLibs"

# aarch64 主机：NDK 的 x86_64 clang 无法执行 → 自动切系统 clang。
if [[ -z "${USE_SYSTEM_CLANG:-}" && "$(uname -m)" == "aarch64" ]]; then
  if ! "$BIN/aarch64-linux-android${API}-clang" --version >/dev/null 2>&1; then
    USE_SYSTEM_CLANG=1
  fi
fi

if [[ "${USE_SYSTEM_CLANG:-}" == "1" ]]; then
  command -v clang >/dev/null || { echo "错误：需安装 clang（apt install clang lld）" >&2; exit 1; }
fi

# 系统 clang 模式：NDK r25+ 的 crt/系统库放在带 API 版本的子目录（如 33/），
# 通用 clang 只认无版本目录 usr/lib/<triple>/。幂等地补齐（已存在则跳过）。
# 另：rustc 对 android 链接 -lunwind，而 NDK 不带 libunwind.a → 编空 stub
# （-C panic=abort 下无 unwind 需求，_Unwind 符号运行时由设备 libc 解析）。
prepare_sysroot() {
  local triple="$1" api="$2"
  local libdir="$SYSROOT/usr/lib/$triple"
  [[ -d "$libdir/$api" ]] || return 0
  # 1) crt*.o
  if [[ ! -f "$libdir/crtbegin_dynamic.o" ]]; then
    cp "$libdir/$API/"crt*.o "$libdir/" 2>/dev/null || true
  fi
  # 2) 系统库软链（liblog/libdl/libc/libm）
  for lib in liblog libdl libc libm; do
    if [[ -f "$libdir/$API/$lib.so" && ! -e "$libdir/$lib.so" ]]; then
      ln -sf "$libdir/$API/$lib.so" "$libdir/$lib.so"
    fi
  done
  # 3) libunwind stub
  if [[ ! -e "$libdir/libunwind.so" ]]; then
    echo "/* stub */" > /tmp/yaya_unwind_stub.c
    clang --target="$triple" --sysroot="$SYSROOT" -shared -fPIC -nostdlib \
      -o "$libdir/libunwind.so" /tmp/yaya_unwind_stub.c
  fi
}

build() {
  local target="$1" abi="$2"
  local upper triple="${target%%[0-9]*}"
  upper="$(echo "$target" | tr 'a-z-' 'A-Z_')"
  echo "== $abi ($target) =="

  if [[ "${USE_SYSTEM_CLANG:-}" == "1" ]]; then
    # NDK sysroot 目录名与 Rust target 不完全一致：armv7 用 arm-linux-androideabi。
    local st="$target"
    [[ "$target" == "armv7-linux-androideabi" ]] && st="arm-linux-androideabi"
    # 实际 API 目录取最大数字子目录（aarch64/x86_64 有版本化目录，armv7 亦然）。
    local apidir=""
    for d in "$SYSROOT/usr/lib/$st/"[0-9]*; do
      [[ -d "$d" ]] && apidir="$d"
    done
    prepare_sysroot "$st" "$(basename "$apidir")"
    export RUSTFLAGS="-C panic=abort -C linker=clang \
      -C link-arg=--target=$target \
      -C link-arg=--sysroot=$SYSROOT \
      -C link-arg=-L -C link-arg=$apidir"
    env "CC_${target}=clang --target=$target --sysroot=$SYSROOT -D__ANDROID_API__=$API" \
        "AR_${target}=ar" \
        "RANLIB_${target}=ranlib" \
        cargo build -p yaya-core-jni --target "$target" --release
    unset RUSTFLAGS
  else
    env "CC_${target}=$BIN/$3" \
        "AR_${target}=$BIN/llvm-ar" \
        "CARGO_TARGET_${upper}_LINKER=$BIN/$3" \
        cargo build -p yaya-core-jni --target "$target" --release
  fi
  mkdir -p "$OUT/$abi"
  cp "target/$target/release/libyaya_core_jni.so" "$OUT/$abi/"
  echo "  -> $OUT/$abi/libyaya_core_jni.so"
}

build aarch64-linux-android   arm64-v8a   "aarch64-linux-android${API}-clang"
build armv7-linux-androideabi armeabi-v7a "armv7a-linux-androideabi${API}-clang"
build x86_64-linux-android    x86_64      "x86_64-linux-android${API}-clang"

echo "完成。"