-- mcp-cache-gateway PostgreSQL schema (Phase 1)
-- 借鉴设计 doc docs/design.md §3.3 + tubi-mcp cache 模式

CREATE TABLE IF NOT EXISTS mcp_request_state (
    request_id      TEXT PRIMARY KEY,
    server_id       TEXT NOT NULL,
    method          TEXT NOT NULL,
    tool_name       TEXT,
    tool_version    TEXT,
    params_hash     TEXT NOT NULL,
    params_json     JSONB NOT NULL,
    result_json     JSONB,
    result_size     INTEGER,
    cache_tier      TEXT,                           -- 'redis' | 'db' | 'both'
    ttl_ms          INTEGER NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    stale_until     TIMESTAMPTZ,                    -- SWR window end
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    invalidated     BOOLEAN DEFAULT FALSE,
    hit_count       INTEGER DEFAULT 0,
    metadata        JSONB
);

CREATE INDEX IF NOT EXISTS idx_params_hash ON mcp_request_state (params_hash);
CREATE INDEX IF NOT EXISTS idx_expires_at_active ON mcp_request_state (expires_at)
    WHERE NOT invalidated;
CREATE INDEX IF NOT EXISTS idx_tool_name ON mcp_request_state (tool_name);
CREATE INDEX IF NOT EXISTS idx_created_at ON mcp_request_state (created_at);

CREATE TABLE IF NOT EXISTS mcp_tool_config (
    tool_name       TEXT PRIMARY KEY,
    tool_version    TEXT,
    ttl_ms          INTEGER NOT NULL DEFAULT 86400000,  -- 1 day default
    time_sensitive  BOOLEAN DEFAULT FALSE,
    cacheable       BOOLEAN DEFAULT TRUE,
    swr_grace_ms    INTEGER,                              -- null = auto
    max_param_size  INTEGER,
    notes           TEXT,
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

-- Track SWR refresh attempts (for metrics + debugging)
CREATE TABLE IF NOT EXISTS mcp_refresh_log (
    id              BIGSERIAL PRIMARY KEY,
    request_id      TEXT NOT NULL,
    tool_name       TEXT NOT NULL,
    status          TEXT NOT NULL,                -- 'success' | 'failure'
    duration_ms     INTEGER,
    error_message   TEXT,
    refreshed_at    TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_refresh_log_tool ON mcp_refresh_log (tool_name, refreshed_at);