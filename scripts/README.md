# 脚本使用说明

## 本地验证流程

在进行提交前，请按以下顺序在本地执行验证命令：

```bash
# 1. 运行 proto 生成脚本
bash scripts/gen-proto.sh

# 2. 检查 git 状态
git status

# 3. 检查变更一致性（若有生成物变更，必须提交）
git diff --exit-code
```

## 目录结构约定

- `proto/agent.proto`：契约唯一源，所有端的 proto 定义
- `android/app/src/main/java/com/yaya/ai/proto/`：Java/Kotlin 生成物目录
- `lib/generated/`：Dart 生成物目录
- `core/src/generated/`：Rust 手写映射目录（可选）

## 工作流 CI 流程

本地验证通过后，请确保提交包含以下文件（按优先级排序）：

1. `proto/agent.proto` - 源头定义
2. `android/app/src/main/java/com/yaya/ai/proto/` - Java 生成物
3. `lib/generated/` - Dart 生成物
4. `core/src/generated/` - Rust 手写映射（若存在）
5. `scripts/gen-proto.sh` - 若有修改

CI 工作流 `.github/workflows/proto.yml` 会在每次 push/PR 时自动运行上述验证，并失败若出现非预期变更。