package com.fuermos.mcp.cache.gateway.bridge.wrongnotebook

import com.fuermos.mcp.cache.gateway.config.ToolConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * WrongNotebookBridge — 5-tool bridge for wrong-notebook.
 *
 * Day 5 design (per spec §5 Day 5 morning+afternoon):
 *   - 5 tools: get_notebook / list_notebooks / add_question / update_question / delete_question
 *   - Each tool: name + description + inputSchema + handler(args, client)
 *   - handler returns JsonElement (raw API response)
 *   - Per-tool cacheability (some are W = NOT idempotent → cacheable=false)
 *
 * Pattern references (借鉴 tubi-mcp/wrongnotebook-mcp-bridge.js):
 *   - TOOLS array + HANDLERS map (JS)
 *   - Per-tool inputSchema with type + properties + description
 *   - cacheable: false for write operations (add/update/delete)
 *
 * Phase 2 scope: add remaining 10 tools (create_notebook, list_questions,
 *   get_question, search_questions, get_stats, export_questions,
 *   practice_record, update_mastery, delete_notebook, update_notebook).
 */
class WrongNotebookBridge(
    private val client: WrongNotebookClient
) {

    private val log = LoggerFactory.getLogger(WrongNotebookBridge::class.java)

    /**
     * Tool definitions exposed via tools/list.
     */
    val tools: List<ToolDefinition> = listOf(
        // 1. list_notebooks — R, idempotent (cacheable)
        ToolDefinition(
            name = "wrongnotebook.list_notebooks",
            description = "List all notebooks (subjects) owned by the currently authenticated user. " +
                "Returns array of notebook objects with id, name, writtenBy, writtenAt, createdAt, updatedAt. " +
                "Idempotent — safe to retry.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", kotlinx.serialization.json.JsonObject(emptyMap()))
                put("additionalProperties", false)
            },
            cacheable = true,
            handler = { args, c -> c.listNotebooks() }
        ),

        // 2. get_notebook — R, idempotent (cacheable)
        ToolDefinition(
            name = "wrongnotebook.get_notebook",
            description = "Fetch a single notebook (subject) by id. Returns full Subject row. " +
                "Idempotent. Returns 404 if id missing or belongs to another user.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Notebook id (required).")
                    })
                })
                put("required", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("id"))))
                put("additionalProperties", false)
            },
            cacheable = true,
            handler = { args, c ->
                val id = args.stringArg("id")
                c.getNotebook(id)
            }
        ),

        // 3. add_question — W, NOT idempotent (NOT cacheable)
        ToolDefinition(
            name = "wrongnotebook.add_question",
            description = "Add a new question (error item) to a notebook. The notebook must exist. " +
                "NOT idempotent — repeated calls create duplicate questions (upstream has 2s dedup window).",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("subject", buildJsonObject {
                        put("type", "string")
                        put("description", "Notebook id (required).")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Question description / error text (required).")
                    })
                    put("errorType", buildJsonObject {
                        put("type", "string")
                        put("description", "Error category (optional, e.g. 'grammar', 'logic').")
                    })
                    put("difficulty", buildJsonObject {
                        put("type", "integer")
                        put("description", "Difficulty 1-5 (optional, default 3).")
                    })
                    put("tags", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Optional tags (array of strings).")
                    })
                })
                put("required", kotlinx.serialization.json.JsonArray(listOf(
                    kotlinx.serialization.json.JsonPrimitive("subject"),
                    kotlinx.serialization.json.JsonPrimitive("description")
                )))
                put("additionalProperties", false)
            },
            cacheable = false,
            handler = { args, c ->
                val subject = args.stringArg("subject")
                c.addQuestion(subject, args)
            }
        ),

        // 4. update_question — W, NOT idempotent (NOT cacheable)
        ToolDefinition(
            name = "wrongnotebook.update_question",
            description = "Update an existing question (error item) by id. " +
                "Partial update — only fields present in arguments are modified.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Question id (required).")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Updated question description.")
                    })
                    put("difficulty", buildJsonObject {
                        put("type", "integer")
                        put("description", "Updated difficulty 1-5.")
                    })
                    put("tags", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Updated tags.")
                    })
                })
                put("required", kotlinx.serialization.json.JsonArray(listOf(
                    kotlinx.serialization.json.JsonPrimitive("id")
                )))
                put("additionalProperties", false)
            },
            cacheable = false,
            handler = { args, c ->
                val id = args.stringArg("id")
                c.updateQuestion(id, args)
            }
        ),

        // 5. delete_question — W, destructive (NOT cacheable)
        ToolDefinition(
            name = "wrongnotebook.delete_question",
            description = "Permanently delete a question (error item) by id. Destructive — no undo.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Question id (required).")
                    })
                })
                put("required", kotlinx.serialization.json.JsonArray(listOf(
                    kotlinx.serialization.json.JsonPrimitive("id")
                )))
                put("additionalProperties", false)
            },
            cacheable = false,
            handler = { args, c ->
                val id = args.stringArg("id")
                c.deleteQuestion(id)
            }
        )
    )

    /**
     * Tool registry indexed by name (for O(1) dispatch).
     */
    private val byName: Map<String, ToolDefinition> = tools.associateBy { it.name }

    /**
     * Get tool definition by name (for tools/list response).
     */
    fun toolDefinition(name: String): ToolDefinition? = byName[name]

    /**
     * Invoke a tool by name with arguments.
     *
     * @throws WrongNotebookApiException on upstream errors
     * @throws IllegalArgumentException on missing required args
     */
    fun callTool(name: String, arguments: JsonObject): JsonElement {
        val tool = byName[name] ?: throw IllegalArgumentException("Unknown tool: $name")
        log.debug("calling tool: {} (cacheable={})", name, tool.cacheable)
        return tool.handler(arguments, client)
    }

    /**
     * Get all tool definitions for tools/list response.
     */
    fun listTools(): List<ToolDefinition> = tools

    companion object {
        /**
         * Convert ToolDefinition list to per-tool ToolConfig (for ToolConfigResolver).
         *
         * Usage:
         *   val configs = bridge.toToolConfigs()
         *   val resolver = ToolConfigResolver.empty().replaceWith(
         *       ToolConfigRoot(tools = configs, defaults = ...)
         *   )
         */
        fun listToToolConfigs(tools: List<ToolDefinition>): List<ToolConfig> {
            return tools.map { tool ->
                ToolConfig(
                    name = tool.name,
                    version = "1.0.0",
                    ttlMs = if (tool.cacheable) 60_000 else 0,  // 1 min for cacheable reads
                    cacheable = tool.cacheable,
                    timeSensitive = false,
                    swrGraceMs = null,
                    maxParamSize = 4096
                )
            }
        }
    }
}

/**
 * Tool definition data class.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonElement,
    val cacheable: Boolean,
    val handler: (args: JsonObject, client: WrongNotebookClient) -> JsonElement
)

/**
 * Helper to extract required string argument from JSON-RPC params.
 */
private fun JsonObject.stringArg(name: String): String {
    val value = this[name] ?: throw IllegalArgumentException("Missing required argument: $name")
    val primitive = value as? JsonPrimitive
        ?: throw IllegalArgumentException("Argument $name must be a string")
    return primitive.contentOrNull ?: throw IllegalArgumentException("Argument $name has no string content")
}