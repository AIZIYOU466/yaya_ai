#!/usr/bin/env bash
# 从 proto/agent.proto 生成各端代码（契约唯一源，见 AGENTS.md R5/三端代码生成）。
# 约定：Java -> android/app/src/main/java/com/yaya/ai/proto/
#        Dart   -> lib/generated/
#        Rust   -> 暂不自动生成，手写映射
set -euo pipefail

# ── 版本固定 ──────────────────────────────────────────────
GRPC_JAVA_VERSION=1.63.0
PROTOC_VERSION=25.3

# ── 架构映射 ──────────────────────────────────────────────
ARCH=$(uname -m)
case "$ARCH" in
  x86_64)   ARCH_TRIPLET="x86_64" ;;
  aarch64)  ARCH_TRIPLET="aarch_64" ;;   # 注意下划线
  *)        echo "错误: 未知架构 $ARCH，需手动安装 protoc-gen-grpc-java"; exit 1 ;;
esac

# ── 工具检查 ──────────────────────────────────────────────
if ! command -v protoc >/dev/null 2>&1; then
  echo "错误: 未找到 protoc。请先运行: sudo apt-get install -y protobuf-compiler" >&2
  exit 1
fi

# ── 创建输出目录 ──────────────────────────────────────────
mkdir -p \
  android/app/src/main/java/com/yaya/ai/proto \
  lib/generated \
  core/src/generated

# ── 下载 protoc-gen-grpc-java ─────────────────────────────
if ! command -v protoc-gen-grpc-java >/dev/null 2>&1; then
  ARCH_EXE="protoc-gen-grpc-java-${GRPC_JAVA_VERSION}-linux-${ARCH_TRIPLET}.exe"
  URL="https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/${GRPC_JAVA_VERSION}/${ARCH_EXE}"
  echo "下载 $URL ..."
  if ! wget -q "$URL" -O /usr/local/bin/protoc-gen-grpc-java; then
    echo "错误: wget 失败，请确保 runner 架构是 x86_64 或 aarch64" >&2
    exit 1
  fi
  chmod +x /usr/local/bin/protoc-gen-grpc-java
  protoc-gen-grpc-java --version || true
fi

# ── Dart：激活 protoc_plugin ─────────────────────────────
if ! command -v protoc-gen-dart >/dev/null 2>&1; then
  echo "未检测到 protoc-gen-dart，尝试 Dart 全局激活 ..."
  dart pub global activate protoc_plugin --no-precompile 2>/dev/null && \
    echo "激活成功" || \
    { echo "首次激活失败，重试一次..."; dart pub global activate protoc_plugin; }
fi

# ── Java + gRPC Java 生成 ─────────────────────────────────
# 生成物将落到 android/app/src/main/java/com/yaya/ai/proto/
PROTO_PATH=proto/agent.proto
JAVA_OUT_DIR=android/app/src/main/java/com/yaya/ai/proto

protoc \
  --proto_path=.:/usr/local/include \
  --java_out="$JAVA_OUT_DIR" \
  --grpc-java_out="$JAVA_OUT_DIR" \
  -I proto \
  "$PROTO_PATH"

echo "Java -> $JAVA_OUT_DIR"

# ── Dart 生成 ─────────────────────────────────────────────
DART_OUT_DIR=lib/generated
mkdir -p "$DART_OUT_DIR"
protoc \
  --proto_path=.:/usr/local/include \
  --dart_out=grpc:$DART_OUT_DIR \
  -I proto \
  "$PROTO_PATH"

echo "Dart -> $DART_OUT_DIR"

# ── Rust：此处保持占位，不自动生成 ────────────────────────
# Rust 端使用 tonic-build 在构建期从 proto 生成，或手写映射。
# 如需自动生成，请扩展：cargo install tonic-protoc 后再添加对应步骤。
echo "Rust: 占位（手写映射），跳过 auto 生成"