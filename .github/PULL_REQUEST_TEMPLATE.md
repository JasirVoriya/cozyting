## 变更概述

<!-- 简述本次 PR 解决的问题或新增的能力，并关联对应 Issue -->
- 关联 Issue：Fixes #（如无请删除）
- 变更方向：`[ ] Rust 核心 (core/)` · `[ ] CLI (cli/)` · `[ ] Android 端 (android/)` · `[ ] iOS 端 / 跨端设计 (design/)` · `[ ] 文档 / 工程化`

## 改动要点

- 
- 

## 自测与验证记录

- [ ] `cargo test --workspace` 通过
- [ ] `cd android && ./gradlew --no-daemon test lintStandardDebug` 通过（无 `NewApi` fatal 报错）
- [ ] 若改动原生代码或构建脚本，已确认 `arm64-v8a` `.so` 保持 16KB ELF LOAD 页对齐（`0x4000`）
- [ ] 若改动涉及真机 / 音箱行为，已在下方如实说明实测环境与未测边界（**不扩大未验证结论**）

### 实测环境（如适用）

- 手机 / 系统：
- 音箱型号与数量：
- 已知未测范围：

## 合规与安全检查

- [ ] 未提交任何签名密钥、口令、`local.properties`、`keystore.properties`、私有日志或原始抓包文件
- [ ] 变更符合本项目 `AGPL-3.0-only` 开源许可，且不引入云端依赖或破坏单 UID 音频捕获 fail-closed 边界
