-- mcp-cache-gateway Phase 2 — backend registry (DB-driven config)
-- 主人 2026-08-08 17:46 拍板: 完全 DB-driven, 删文件 fallback
-- 失败 fail fast (主+backup DB 都 down = gateway 拒绝服务)
--
-- 借鉴 spec §4 Phase 2:
--   - 2.1 BackendsRegistry (DB-driven + DB backup fallback)
--   - 2.2 ToolConfigResolver (tools.yaml → mcp_tool_config 表 — V1 已创,本 V2 不动)
--   - 2.3 Hot reload (LISTEN/NOTIFY mcp_backend_changed)
--   - 2.4 Y 架构 GeneralProxy orchestrator
--
-- 关键约束:
--   - secrets 不在 DB 明文 — 用 secret_ref 指向 ~/.openclaw/state/*.env
--   - audit trigger 记录所有 UPDATE/DELETE
--   - NOTIFY channel 'mcp_backend_changed' 用于 hot reload
--   - mcp_tool_config (V1) 已有 — V2 不重复
--   - serverId = mcp_backend.name (FK-like reference, no enforced FK)

-- ============================================
-- mcp_backend: 描述每个 MCP server 配置
-- ============================================
CREATE TABLE IF NOT EXISTS mcp_backend (
    name              TEXT PRIMARY KEY,                      -- backend identifier (e.g. 'wrongnotebook', 'wigolo')
    display_name      TEXT NOT NULL,                          -- human-readable name
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,          -- enabled flag (allows disable without delete)
    cmd               TEXT NOT NULL,                          -- executable (e.g. 'java', 'node', 'python3')
    args              JSONB NOT NULL DEFAULT '[]',           -- command line args as JSON array
    cwd               TEXT,                                   -- working directory (optional)
    spawn_timeout_ms  INTEGER NOT NULL DEFAULT 5000,         -- subprocess spawn timeout
    idle_timeout_ms   INTEGER NOT NULL DEFAULT 60000,        -- idle cleanup threshold
    max_restarts      INTEGER NOT NULL DEFAULT 3,             -- max restart attempts
    eager             BOOLEAN NOT NULL DEFAULT FALSE,         -- spawn at startup vs lazy
    protocol          TEXT NOT NULL DEFAULT 'stdio',          -- 'stdio' | 'http' (Phase 2 = stdio only)
    notes             TEXT,                                   -- free-form notes
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version           INTEGER NOT NULL DEFAULT 1             -- optimistic lock + audit
);

-- Index for hot reload watcher (frequently scanned for enabled backends)
CREATE INDEX IF NOT EXISTS idx_backend_enabled ON mcp_backend (enabled) WHERE enabled = TRUE;

-- ============================================
-- mcp_backend_env: 每个 backend 的环境变量 (secret_ref 模式)
-- ============================================
-- 设计:
--   - 普通 env vars: 直接存 value (非 sensitive)
--   - secrets (passwords, tokens): value 留空, secret_ref 指向 OpenClaw state dir
--   - secret_ref 格式: "file:/path/to/file#KEY_NAME" 或 "literal:sensitive_value" (仅 dev)
--
-- 借鉴 tubi-mcp/wrong-notebook-bridge.js: env vars 在进程 spawn 时 set
-- 借鉴 主人 17:31 acceptance: secrets 在 OpenClaw state dir, chmod 600
CREATE TABLE IF NOT EXISTS mcp_backend_env (
    backend_name      TEXT NOT NULL REFERENCES mcp_backend(name) ON DELETE CASCADE,
    env_key           TEXT NOT NULL,
    env_value         TEXT,                                   -- plaintext value (for non-sensitive vars)
    secret_ref        TEXT,                                   -- pointer to secret (e.g. 'file:/home/fuermos/.openclaw/state/wrongnotebook-credentials.env#WRONGNOTEBOOK_PASSWORD')
    is_secret         BOOLEAN NOT NULL DEFAULT FALSE,         -- marks as sensitive (audit + redact)
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (backend_name, env_key),
    -- Exactly one of (env_value, secret_ref) must be set
    CHECK ((env_value IS NOT NULL AND secret_ref IS NULL) OR (env_value IS NULL AND secret_ref IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_backend_env_secret_ref ON mcp_backend_env (secret_ref) WHERE secret_ref IS NOT NULL;

-- ============================================
-- mcp_backend_audit: 变更审计 (trigger 自动)
-- ============================================
-- 借鉴 design.md §6 一致性保证 + audit trail 最佳实践
-- 触发: UPDATE / DELETE on mcp_backend OR mcp_backend_env → INSERT into mcp_backend_audit
CREATE TABLE IF NOT EXISTS mcp_backend_audit (
    id              BIGSERIAL PRIMARY KEY,
    backend_name    TEXT NOT NULL,
    operation       TEXT NOT NULL CHECK (operation IN ('INSERT', 'UPDATE', 'DELETE')),
    table_name      TEXT NOT NULL CHECK (table_name IN ('mcp_backend', 'mcp_backend_env')),
    changed_fields  JSONB,                                  -- JSON object with old/new values
    changed_by      TEXT,                                    -- optional username / 'system'
    changed_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_backend_audit_name_time ON mcp_backend_audit (backend_name, changed_at DESC);
CREATE INDEX IF NOT EXISTS idx_backend_audit_time ON mcp_backend_audit (changed_at DESC);

-- ============================================
-- Triggers: 自动 audit + NOTIFY for hot reload
-- ============================================

-- mcp_backend INSERT/UPDATE/DELETE audit + notify
CREATE OR REPLACE FUNCTION fn_mcp_backend_audit() RETURNS TRIGGER AS $$
DECLARE
    changed_fields JSONB;
    op TEXT;
    backend TEXT;
BEGIN
    IF (TG_OP = 'INSERT') THEN
        op := 'INSERT';
        backend := NEW.name;
        changed_fields := to_jsonb(NEW);
    ELSIF (TG_OP = 'UPDATE') THEN
        op := 'UPDATE';
        backend := NEW.name;
        -- Build JSON with only changed fields (old vs new)
        changed_fields := jsonb_build_object(
            'old', to_jsonb(OLD),
            'new', to_jsonb(NEW)
        );
    ELSIF (TG_OP = 'DELETE') THEN
        op := 'DELETE';
        backend := OLD.name;
        changed_fields := to_jsonb(OLD);
    END IF;

    INSERT INTO mcp_backend_audit (backend_name, operation, table_name, changed_fields)
        VALUES (backend, op, 'mcp_backend', changed_fields);

    -- NOTIFY for hot reload (Day 2.3 McpBackendWatcher)
    PERFORM pg_notify('mcp_backend_changed', json_build_object('backend', backend, 'op', op)::text);

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_mcp_backend_audit ON mcp_backend;
CREATE TRIGGER trg_mcp_backend_audit
    AFTER INSERT OR UPDATE OR DELETE ON mcp_backend
    FOR EACH ROW EXECUTE FUNCTION fn_mcp_backend_audit();

-- mcp_backend_env INSERT/UPDATE/DELETE audit (env values redacted if is_secret=true)
CREATE OR REPLACE FUNCTION fn_mcp_backend_env_audit() RETURNS TRIGGER AS $$
DECLARE
    changed_fields JSONB;
    op TEXT;
    backend TEXT;
    redacted_value TEXT := '***REDACTED***';
BEGIN
    IF (TG_OP = 'INSERT') THEN
        op := 'INSERT';
        backend := NEW.backend_name;
        changed_fields := jsonb_build_object(
            'env_key', NEW.env_key,
            'env_value', CASE WHEN NEW.is_secret THEN redacted_value ELSE NEW.env_value END,
            'secret_ref', NEW.secret_ref,
            'is_secret', NEW.is_secret
        );
    ELSIF (TG_OP = 'UPDATE') THEN
        op := 'UPDATE';
        backend := NEW.backend_name;
        changed_fields := jsonb_build_object(
            'env_key', NEW.env_key,
            'old_env_value', CASE WHEN OLD.is_secret THEN redacted_value ELSE OLD.env_value END,
            'new_env_value', CASE WHEN NEW.is_secret THEN redacted_value ELSE NEW.env_value END,
            'old_secret_ref', OLD.secret_ref,
            'new_secret_ref', NEW.secret_ref,
            'is_secret', NEW.is_secret
        );
    ELSIF (TG_OP = 'DELETE') THEN
        op := 'DELETE';
        backend := OLD.backend_name;
        changed_fields := jsonb_build_object(
            'env_key', OLD.env_key,
            'is_secret', OLD.is_secret
        );
    END IF;

    INSERT INTO mcp_backend_audit (backend_name, operation, table_name, changed_fields)
        VALUES (backend, op, 'mcp_backend_env', changed_fields);

    PERFORM pg_notify('mcp_backend_changed', json_build_object('backend', backend, 'op', op, 'env_key', COALESCE(NEW.env_key, OLD.env_key))::text);

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_mcp_backend_env_audit ON mcp_backend_env;
CREATE TRIGGER trg_mcp_backend_env_audit
    AFTER INSERT OR UPDATE OR DELETE ON mcp_backend_env
    FOR EACH ROW EXECUTE FUNCTION fn_mcp_backend_env_audit();

-- ============================================
-- updated_at 自动 trigger (mcp_backend)
-- ============================================
CREATE OR REPLACE FUNCTION fn_mcp_backend_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    NEW.version = OLD.version + 1;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_mcp_backend_updated_at ON mcp_backend;
CREATE TRIGGER trg_mcp_backend_updated_at
    BEFORE UPDATE ON mcp_backend
    FOR EACH ROW EXECUTE FUNCTION fn_mcp_backend_updated_at();

-- ============================================
-- Seed data: 预填 wrongnotebook (Phase 2.5 接入)
-- ============================================
-- 注意: secrets 通过 secret_ref 引用, 不在 SQL 中明文
-- 主人 17:31 acceptance: secrets 在 OpenClaw state dir (chmod 600)
INSERT INTO mcp_backend (name, display_name, enabled, cmd, args, protocol, notes)
VALUES (
    'wrongnotebook',
    'wrongnotebook (Phase 1 bridge)',
    TRUE,
    'java',
    '["-jar", "/home/fuermos/dev/mcp-cache-gateway/build/libs/mcp-cache-gateway-0.1.0.jar"]',
    'stdio',
    'Phase 1 wrongnotebook 5 tools (list/get/add/update/delete). Bridge is in-process Kotlin client; this entry exists for Day 2 Y-architecture registry parity.'
) ON CONFLICT (name) DO NOTHING;

-- wrongnotebook env vars (secrets via secret_ref)
INSERT INTO mcp_backend_env (backend_name, env_key, env_value, is_secret)
VALUES
    ('wrongnotebook', 'WRONGNOTEBOOK_URL', 'http://localhost:3032', FALSE),
    ('wrongnotebook', 'WRONGNOTEBOOK_USER', 'fuermos', FALSE)
ON CONFLICT (backend_name, env_key) DO NOTHING;

INSERT INTO mcp_backend_env (backend_name, env_key, secret_ref, is_secret)
VALUES
    ('wrongnotebook', 'WRONGNOTEBOOK_PASSWORD', 'file:/home/fuermos/.openclaw/state/wrongnotebook-credentials.env#WRONGNOTEBOOK_PASSWORD', TRUE)
ON CONFLICT (backend_name, env_key) DO NOTHING;

-- Note: Phase 1 in-process bridge doesn't spawn subprocess, so cmd/args are placeholders.
-- Phase 2.5 (1h) will rewire to either subprocess spawn OR keep in-process bridge with DB-driven config.
