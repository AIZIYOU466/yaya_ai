#!/usr/bin/env bash
# 启动 YAYai 桌面 Agent 服务：gRPC :50051（核心协议）+ HTTP /health /debug :8082
# 全量推理：LLAMA_LIB_DIR=/path/to/llama/lib cargo run --release --features full-llama（见 AGENTS.md R4）
set -euo pipefail
cd "$(dirname "$0")/../.."
exec cargo run --release -p yaya-agent-server -- "$@"
