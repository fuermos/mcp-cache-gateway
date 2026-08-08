# Wrongnotebook Bridge

In-process Kotlin client for the wrong-notebook MCP API (Phase 1).

## Files

### `WrongNotebookAuth.kt`
NextAuth.js credentials auto-login + cookie persistence:
- `getSessionCookie()` → auto-login if missing or expired
- `login()` → fetch CSRF token + POST credentials
- `~/.openclaw/state/wrongnotebook-credentials.json` (chmod 600)
- Re-login at 7 days (NextAuth default 30)

### `WrongNotebookClient.kt`
HTTP client (5 methods) using `java.net.http.HttpClient`:
- `listNotebooks()` → GET /api/notebooks
- `getNotebook(id)` → GET /api/notebooks/:id
- `addQuestion(subjectId, payload)` → POST /api/error-items
- `updateQuestion(id, payload)` → PUT /api/error-items/:id
- `deleteQuestion(id)` → DELETE /api/error-items/:id

HTTP error classification:
- 401 → `WRONGNOTEBOOK_HTTP_401` (auth expired)
- 404 → `WRONGNOTEBOOK_HTTP_404`
- 409, 422 → `WRONGNOTEBOOK_HTTP_409`/`422`
- 500-599 → `WRONGNOTEBOOK_HTTP_5xx`

### `WrongNotebookBridge.kt`
5 tool definitions + handlers:
1. `wrongnotebook.list_notebooks` (R, cacheable=true, TTL=60s)
2. `wrongnotebook.get_notebook` (R, cacheable=true, TTL=60s)
3. `wrongnotebook.add_question` (W, cacheable=false, TTL=0)
4. `wrongnotebook.update_question` (W, cacheable=false, TTL=0)
5. `wrongnotebook.delete_question` (W, cacheable=false, TTL=0)

Helper: `listToToolConfigs(tools)` → `List<ToolConfig>` for `ToolConfigResolver`.

## Pattern References

- `tubi-mcp/wrongnotebook-mcp-bridge.js` — original 15-tool JS bridge (we take 5)
- `tubi-mcp/wrong-notebook-bridge.js` — NextAuth flow + cookie persistence
- `orchestrator/GatewayOrchestrator.kt` — Day 5 routing fix (`wrongnotebook.*` → bridge)

## 5 Tools Reference

### 1. `wrongnotebook.list_notebooks`
- **Read** all notebooks (subjects) for the authenticated user
- **Returns**: array of `{id, name, writtenBy, writtenBySession, writtenAt, createdAt, updatedAt}`
- **Cacheable**: true (TTL 60s)
- **Idempotent**: yes

### 2. `wrongnotebook.get_notebook`
- **Read** a single notebook by id
- **Returns**: full Subject row
- **Cacheable**: true (TTL 60s)
- **Idempotent**: yes
- **404**: if id missing or belongs to another user

### 3. `wrongnotebook.add_question`
- **Write** a new question (error item) to a notebook
- **Returns**: created question with id
- **Cacheable**: false (write op)
- **NOT idempotent**: upstream has 2s dedup window but distinct calls = distinct rows

### 4. `wrongnotebook.update_question`
- **Update** an existing question by id
- **Returns**: updated question
- **Cacheable**: false (write op)
- **NOT idempotent**: partial update semantics

### 5. `wrongnotebook.delete_question`
- **Delete** a question by id
- **Returns**: empty object
- **Cacheable**: false (destructive)
- **Idempotent**: yes (delete same id twice = same result)

## Usage

```kotlin
val auth = WrongNotebookAuth(
    baseUrl = "http://localhost:3032",
    username = "fuermos",
    password = System.getenv("WRONGNOTEBOOK_PASSWORD")!!
)
val client = WrongNotebookClient(auth)
val bridge = WrongNotebookBridge(client)

// Get tools/list
val tools = bridge.listTools()  // returns 5 ToolDefinitions

// Call a tool
val result = bridge.callTool(
    "wrongnotebook.list_notebooks",
    JsonObject(emptyMap())
)

// In orchestrator:
val resolver = ToolConfigResolver.empty().replaceWith(
    ToolConfigRoot(tools = WrongNotebookBridge.listToToolConfigs(bridge.listTools()))
)
val orchestrator = GatewayOrchestrator(
    lookup, write, servers, resolver,
    wrongNotebookBridge = bridge
)
```

## Future

Phase 2+ may add the remaining 10 tools:
- `wrongnotebook.create_notebook`
- `wrongnotebook.delete_notebook`
- `wrongnotebook.update_notebook`
- `wrongnotebook.list_questions`
- `wrongnotebook.get_question`
- `wrongnotebook.search_questions`
- `wrongnotebook.get_stats`
- `wrongnotebook.export_questions`
- `wrongnotebook.practice_record`
- `wrongnotebook.update_mastery`
