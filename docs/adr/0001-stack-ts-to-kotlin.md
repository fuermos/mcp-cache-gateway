# ADR-0001: Switch Tech Stack from Node.js/TypeScript to Kotlin/Spring Boot

## Status

**Accepted** — 2026-08-08

## Context

mcp-cache-gateway 最初设计为 Node.js + TypeScript（fork `ZeroClue/mcp-cache-proxy`），但 **fuermos 2026-08-08 11:11 提出 TS 不稳定经常掉线**，希望用 **Next.js 或 Spring + Kotlin** 替代。

## Decision

**采用 Spring Boot 3 + Kotlin 1.9 作为 mcp-cache-gateway 的实现栈**。

### 决策对比

| 维度 | Next.js | Spring + Kotlin (✓) | Node.js/TS |
|------|---------|---------------------|------------|
| 设计目标 | React 全栈 web 框架 | daemon 服务 | 通用脚本 |
| stdio JSON-RPC | ❌ 不擅长 | ✅ zt-exec 成熟 | ✅ child_process |
| 长跑稳定性 | ⚠️ V8 GC leak | ✅ JVM 内存管理 | ⚠️ V8 GC leak |
| 进程管理 | ⚠️ child_process 边界 case | ✅ zt-exec | ⚠️ child_process |
| Redis client | ❌ 需第三方 | ✅ Spring Data Redis | ✅ ioredis |
| PostgreSQL client | ❌ 需第三方 | ✅ Spring Data JDBC | ✅ pg |
| MCP SDK 官方 | ❌ 无官方 | ✅ Kotlin SDK | ✅ TS SDK |
| **生产稳定性** | ⚠️ | ✅ **企业级** | ⚠️ |
| 启动时间 | ⚠️ ~1s | ⚠️ ~2s | ✅ ~100ms |
| 内存 | ⚠️ | ⚠️ ~200MB | ✅ ~50MB |
| 部署 | ⚠️ 笨重 | ✅ uber-jar | ✅ 单 binary |

**Next.js 不适用**：sidecar / daemon / stdio transport 不是它的战场。

## Consequences

### 正面
- ✅ **JVM 长跑稳定性**直接解决主人担心的 "TS 掉线" 问题
- ✅ Spring 生态（Actuator / Micrometer / Data Redis / JDBC）成熟
- ✅ MCP Kotlin SDK 官方支持，wire-format 兼容
- ✅ zt-exec 进程管理稳定（Kotlin-friendly 包装 child_process）

### 负面
- ❌ **不能直接 fork mcp-cache-proxy 代码**（语言不通），改为**模式移植**（SWR / negative / lazy 算法照搬）
- ⏱️ **多花 1-2 天**（Phase 1 从 3 天 → 4-5 天 → 实际 7 天，因 scope 扩大到含 wrongnotebook migration）
- 💾 **更高内存占用**（~200MB vs ~50MB）
- ⏱️ **JVM warm-up**（~2s 启动 vs Node.js ~100ms）

### 风险
- shrek 之前以 Node.js / Spring 双向有经验（tubi-springcloud），Kotlin 学习成本低
- MCP Kotlin SDK 相对较新（v0.5.0），可能遇 edge case

## Alternatives Considered

1. **保持 Node.js/TS**——否决，主人明确表达 TS 不稳定
2. **Go**——未考虑，主人没提
3. **Python**——未考虑，主人没提
4. **Rust**——未考虑，学习成本高

## References

- 主人决策：fuermos 2026-08-08 11:11 飞书 DM
- shrek 经验：tubi-springcloud 服务栈（Spring + Kotlin）
- MCP Kotlin SDK：https://github.com/modelcontextprotocol/kotlin-sdk
- Spring Boot 3.3：https://spring.io/projects/spring-boot

---

**作者**：skill-master（智多星）
**批准**：fuermos
**生效**：2026-08-08 Phase 1 启动时