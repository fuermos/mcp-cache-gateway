# ADR-0002: Gradual Migration Strategy — Track A/B/C

## Status

**Accepted** — 2026-08-08

## Context

mcp-cache-gateway 不只是 sidecar cache，**将逐步取代 tubi-mcp**（fuermos 2026-08-08 11:14 明确）。tubi-mcp 当前有 4 个 bridge：
- `wrongnotebook-mcp-bridge.js`（15 tools，最常用）
- `wigolo-bridge.js`（web 工具，AGPL 隔离）
- `exameow-bridge.js`（AI 出题 / 判卷）
- `pdf-router.js`（PDF 路由）

## Decision

**采用三阶段渐进迁移策略**：Track A（sidecar）→ Track B（吸收 bridge）→ Track C（退休 tubi-mcp）。

## Migration Tracks

### Track A — Sidecar 模式（Phase 1 · 0-10 天 · 7 天 ship）

**目标**：mcp-cache-gateway 与 tubi-mcp 并存，**只接管 new** 或**自愿接管**的 MCP 请求。

**Phase 1 范围**：
- Spring Boot + Kotlin 骨架
- 两 tier cache + request_id + per-tool TTL + SWR + negative cache + lazy server loading
- wrongnotebook bridge **部分迁移**（5 个最常用 tools）

**优势**：
- ✅ 零风险（随时 rollback）
- ✅ 渐进式（每日 ship 可用）
- ✅ 主人可控制每个 bridge 切换时机

**劣势**：
- ⚠️ 运维两套服务（mcp-cache-gateway + tubi-mcp）
- ⚠️ tubi-mcp 的 bridge 仍需维护直到迁移完

**结束条件**：Day 7 — mcp-cache-gateway 能独立处理 wrongnotebook 5 tools，主人确认可切换 OpenClaw MCP config。

---

### Track B — 渐进吸收（Phase 2-4 · 10-30 天）

**目标**：把 tubi-mcp 4 个 bridge 逐个迁到 mcp-cache-gateway，每个 bridge 迁完在 tubi-mcp 标 deprecated。

**优先级**：
1. **wrongnotebook**（15 tools 中剩余 10）— Phase 2，3 天
2. **wigolo**（web 工具）— Phase 3，5 天（含 Streamable HTTP）
3. **exameow**（AI 出题）— Phase 4，3 天
4. **pdf-router**（PDF）— Phase 4，2 天

**优势**：
- ✅ 每个 bridge 独立验证（pikachu verify pattern）
- ✅ 主人可暂停 / 回滚任意阶段
- ✅ shrek 专注一个 bridge 不分心

**劣势**：
- ⚠️ 总时间长（10-30 天）
- ⚠️ 两套服务持续期间需 dual-config

**结束条件**：所有 4 bridge 迁完 + tubi-mcp 4 个 bridge 标 deprecated + 主人 OpenClaw config 全切到 mcp-cache-gateway。

---

### Track C — 退休 tubi-mcp（Phase 5+ · 30+ 天）

**目标**：tubi-mcp 标 archived，README 指向 mcp-cache-gateway。

**动作**：
- [ ] tubi-mcp repo 加 `ARCHIVED.md`，README 顶部 banner："This project is archived. Use [mcp-cache-gateway](https://github.com/fuermos/mcp-cache-gateway) instead."
- [ ] tubi-mcp 4 个 bridge 文件不动（保留作 reference）
- [ ] 主人 OpenClaw config 全切到 mcp-cache-gateway
- [ ] 监控 tubi-mcp 1 个月，确认无用户再调用

**优势**：
- ✅ 单一服务（降低复杂度）
- ✅ 维护焦点集中

**劣势**：
- ⚠️ 老的 tubi-mcp 集成需手动迁移

**结束条件**：tubi-mcp archived + 主人确认无遗留调用。

---

## Migration Validation Protocol

每个 bridge 迁移完成 → pikachu 独立 verify：

1. ✅ shrek ship 完整（commit + doc + smoke + 无 hardcoded creds）
2. ✅ pikachu 独立 clone + 跑 e2e smoke
3. ✅ 性能 benchmark 对比（tubi-mcp vs mcp-cache-gateway）
4. ✅ 主人 OpenClaw config 切换 + 监控 1 周

**只有全部通过才进入下一 bridge**。

---

## Consequences

### 正面
- ✅ 风险可控（任何阶段可回滚）
- ✅ 渐进 ship（每日可用）
- ✅ 主人控制节奏
- ✅ shrek 不被 scope creep 压垮

### 负面
- ⏱️ 总时间长（30+ 天）
- ⚠️ 短期内双服务运维
- ⚠️ dual-config 期间文档要清楚标注

### 风险
- 主人需求变化（mid-track scope 调整）— 缓解：每 7 天一次 review
- tubi-mcp 突然 broken 需要紧急迁移 — 缓解：Track A 已能 partial 接管
- pikachu verify 不及时 — 缓解：skill-master 协调，24h 内 escalate

---

## Open Questions

1. wrongnotebook 15 tools 全部迁完后，tubi-mcp 还需不需要保留 mcp-cache-proxy.js 作为 fallback？
2. Streamable HTTP transport 在 Track B 哪个阶段引入？
3. Adaptive TTL tuning（opt-in）何时启用？

---

## References

- 主人决策：fuermos 2026-08-08 11:14 飞书 DM
- tubi-mcp 源码：`<TUBI_MCP_HOME>/`
- mcp-cache-gateway Phase 1 spec：`memory/2026-08-08-phase1-spec.md`

---

**作者**：skill-master（智多星）
**批准**：fuermos
**生效**：2026-08-08 Phase 1 启动时