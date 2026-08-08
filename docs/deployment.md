# mcp-cache-gateway Deployment

## Prerequisites

- **Java 21** (LTS, toolchain auto-downloaded by Gradle)
- **PostgreSQL 16+** (for Tier 2 cache + Flyway migrations)
- **Redis 7+** (for Tier 1 cache)
- **wrong-notebook** instance (Phase 1 backend, default `http://localhost:3032`)
- **NextAuth credentials** for wrong-notebook (cloud or self-hosted)

## Build

```bash
cd ~/dev/mcp-cache-gateway

# Fetch dependencies (online)
./gradlew assemble

# Or offline (if deps cached)
./gradlew assemble --offline
```

Outputs:
- `build/libs/mcp-cache-gateway-0.1.0.jar` (38 MB fat jar with all deps)
- `build/libs/mcp-cache-gateway-0.1.0-plain.jar` (262 KB thin jar)

## Database Setup

### PostgreSQL

```bash
# Create DB + user
sudo -u postgres psql <<EOF
CREATE DATABASE mcp_cache;
CREATE USER mcp_cache WITH PASSWORD '${POSTGRES_PASSWORD}';
GRANT ALL PRIVILEGES ON DATABASE mcp_cache TO mcp_cache;
EOF

# Verify connection
PGPASSWORD="${POSTGRES_PASSWORD}" psql -h 127.0.0.1 -U mcp_cache -d mcp_cache -c "SELECT 1;"
```

### Flyway Migration

Flyway runs automatically on Spring Boot startup. Tables created:
- `mcp_request_state` (cache entries)
- `mcp_tool_config` (per-tool TTL config)
- `mcp_refresh_log` (SWR refresh audit)
- `flyway_schema_history` (Flyway metadata)

To manually apply:
```bash
PGPASSWORD="${POSTGRES_PASSWORD}" psql -h 127.0.0.1 -U mcp_cache -d mcp_cache \
  -f src/main/resources/db/migration/V1__initial_schema.sql
```

## Redis Setup

```bash
# Install
sudo apt install -y redis-server

# Verify
redis-cli -h 127.0.0.1 -p 6379 PING
# Expected: PONG

# Configure for cache (maxmemory + LRU eviction)
sudo tee -a /etc/redis/redis.conf <<EOF
maxmemory 1gb
maxmemory-policy allkeys-lru
EOF
sudo systemctl restart redis-server
```

## Environment Variables

```bash
# PostgreSQL (required)
export POSTGRES_USER=mcp_cache
export POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
export POSTGRES_URL=jdbc:postgresql://localhost:5432/mcp_cache

# Redis (uses defaults from application.yml if not set)
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379

# Wrong-notebook backend (Phase 1)
export WRONGNOTEBOOK_URL=http://localhost:3032
export WRONGNOTEBOOK_USER=your_nextauth_user
export WRONGNOTEBOOK_PASSWORD=${WRONGNOTEBOOK_PASSWORD}
```

Store these in `~/.openclaw/state/mcp-cache-gateway.env` (chmod 600):

```bash
cat > ~/.openclaw/state/mcp-cache-gateway.env <<EOF
export POSTGRES_USER=mcp_cache
export POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
export POSTGRES_URL=jdbc:postgresql://localhost:5432/mcp_cache
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export WRONGNOTEBOOK_URL=http://localhost:3032
export WRONGNOTEBOOK_USER=your_nextauth_user
export WRONGNOTEBOOK_PASSWORD=${WRONGNOTEBOOK_PASSWORD}
EOF
chmod 600 ~/.openclaw/state/mcp-cache-gateway.env
```

## Running

### Development (stdio mode)

```bash
source ~/.openclaw/state/mcp-cache-gateway.env
cd ~/dev/mcp-cache-gateway
./gradlew bootRun --offline
```

The gateway reads JSON-RPC from stdin and writes responses to stdout.

### Production (JAR)

```bash
source ~/.openclaw/state/mcp-cache-gateway.env
java -jar build/libs/mcp-cache-gateway-0.1.0.jar
```

### systemd (Linux)

Create `/etc/systemd/system/mcp-cache-gateway.service`:

```ini
[Unit]
Description=mcp-cache-gateway
After=network.target postgresql.service redis-server.service

[Service]
Type=simple
User=fuermos
EnvironmentFile=<OPENCLAW_STATE_DIR>/mcp-cache-gateway.env
WorkingDirectory=<MCP_CACHE_GATEWAY_HOME>
ExecStart=/usr/bin/java -jar <MCP_CACHE_GATEWAY_HOME>/build/libs/mcp-cache-gateway-0.1.0.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now mcp-cache-gateway
sudo systemctl status mcp-cache-gateway
```

Note: systemd mode is for `bootRun` style. For stdio JSON-RPC, integrate with OpenClaw MCP config.

## OpenClaw Integration

Update `~/.openclaw/config/mcp.json` (or wherever OpenClaw MCP config lives):

```json
{
  "mcpServers": {
    "mcp-cache-gateway": {
      "command": "java",
      "args": ["-jar", "<MCP_CACHE_GATEWAY_HOME>/build/libs/mcp-cache-gateway-0.1.0.jar"],
      "env": {
        "POSTGRES_USER": "mcp_cache",
        "POSTGRES_PASSWORD": "${POSTGRES_PASSWORD}",
        "WRONGNOTEBOOK_URL": "http://localhost:3032"
      }
    }
  }
}
```

Or via `bootRun`:

```json
{
  "mcpServers": {
    "mcp-cache-gateway": {
      "command": "<MCP_CACHE_GATEWAY_HOME>/gradlew",
      "args": ["bootRun", "--offline"],
      "cwd": "<MCP_CACHE_GATEWAY_HOME>"
    }
  }
}
```

## Health Check

The gateway exposes Spring Boot Actuator endpoints (with `spring-boot-starter-actuator` enabled):

```bash
# Health (only available when web-application-type is not "none")
curl -s http://localhost:3852/actuator/health | jq
```

Phase 1 stdio mode disables HTTP listener (`web-application-type: none` in application.yml). Health check via:
1. Verify process is running: `ps -ef | grep mcp-cache-gateway`
2. Verify Redis connection: `redis-cli PING`
3. Verify PG connection: `psql -c "SELECT 1"`
4. Verify Flyway migrations: `psql -c "SELECT version FROM flyway_schema_history"`

## Troubleshooting

### Flyway migration fails

```
org.flywaydb.core.api.FlywayException: Validate failed: ...
```

Solution: Check `flyway_schema_history` table for failed migrations:
```sql
SELECT * FROM flyway_schema_history;
```

### Redis connection refused

```
RedisConnectException: Unable to connect to 127.0.0.1:6379
```

Solution:
```bash
sudo systemctl status redis-server
sudo systemctl start redis-server
```

### PostgreSQL authentication failed

```
FATAL: password authentication failed for user "mcp_cache"
```

Solution: Reset password in `~/.openclaw/state/mcp-cache-gateway.env` and verify with `psql`.

### wrong-notebook 401 Unauthorized

```
WrongNotebookApiException: WRONGNOTEBOOK_HTTP_401
```

Solution: Re-login by deleting `~/.openclaw/state/wrongnotebook-credentials.json` and retrying. Auth will re-run on next request.

### Out of memory (OOM)

```
java.lang.OutOfMemoryError: Java heap space
```

Solution: Increase JVM heap:
```bash
export JAVA_OPTS="-Xmx2g -Xms512m"
java -jar build/libs/mcp-cache-gateway-0.1.0.jar
```

## Upgrade Path

Phase 1 → Phase 2 migration:

1. **Replace bridge**: Move Phase 1 wrongnotebook from inline bridge to subprocess bridge (`examples/servers.yaml` has the template)
2. **Add monitoring**: Re-enable `spring-boot-starter-actuator` + Prometheus metrics
3. **Scale Redis**: Move to Redis Cluster for sharding
4. **TLS**: Add SSL/TLS for wrong-notebook HTTP client
5. **Multi-user**: Add auth.scope to distinguish users (breaks cross-user cache sharing)

## Reference

- [architecture.md](architecture.md) — System architecture
- [performance.md](performance.md) — Benchmark results + tuning
- [../README.md](../README.md) — Quick start
