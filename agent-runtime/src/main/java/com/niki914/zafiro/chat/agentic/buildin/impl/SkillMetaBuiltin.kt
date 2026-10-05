package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.TextResultBuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import com.niki914.zafiro.settings.RuntimeEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent 自己创建和维护 Skill。文件落在 skills/<id>/SKILL.md，下一轮 prompt 目录会看到。
 * 同轮可以用 load_skill 读回。不提供改执行规则或 MCP 的入口。
 */
class SkillMetaBuiltin : TextResultBuiltinTool() {
    override val name: String = "skill_meta"

    override val description: String =
        "Create, replace, or delete a Zafiro skill stored as SKILL.md. " +
            "Actions: write (id, content, optional overwrite), delete (id). " +
            "Content should start with YAML frontmatter (name, description) so the prompt catalog " +
            "can list it. A written skill is enabled by default and can be loaded immediately with " +
            "load_skill; the available_skills block updates on the next turn. " +
            "overwrite defaults to false and refuses to replace an existing skill. " +
            "Do not use this tool to change execution rules, permissions, or MCP servers."

    override val defaultEnabled: Boolean = true

    override val inputSchemaJson: String? get() = SCHEMA

    override suspend fun invokeText(request: BuiltinToolRequest): TextToolResult {
        val args = try {
            parseArgs(request.argumentsJson)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            return TextToolResult.failure(
                code = "INVALID_ARGUMENTS_JSON",
                message = "skill_meta arguments must be a JSON object. " +
                    "Example: {\"action\":\"write\",\"id\":\"wake-windows\",\"content\":\"---\\nname: Wake Windows\\ndescription: ...\\n---\\n...\"}",
            )
        }
        return try {
            when (args.action) {
                "write" -> write(args)
                "delete" -> delete(args.id)
                else -> TextToolResult.failure(
                    code = "UNKNOWN_ACTION",
                    message = "Unknown action '${args.action}'. Use write or delete.",
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            TextToolResult.failure(
                code = "SKILL_META_ERROR",
                message = throwable.message ?: "skill_meta failed.",
            )
        }
    }

    private suspend fun write(args: Args): TextToolResult {
        val id = args.id?.trim().orEmpty()
        if (id.isBlank()) {
            return TextToolResult.failure(
                code = "MISSING_SKILL_ID",
                message = "write requires a non-blank id, such as wake-windows.",
            )
        }
        val content = args.content.orEmpty()
        if (content.isBlank()) {
            return TextToolResult.failure(
                code = "MISSING_CONTENT",
                message = "write requires SKILL.md content.",
            )
        }
        val validation = RuntimeEnvironment.awaitSettingsGateway().writeSkill(
            id = id,
            content = content,
            overwrite = args.overwrite,
        )
        if (validation != null) {
            return TextToolResult.failure(
                code = "VALIDATION_FAILED",
                message = "${validation.field}: ${validation.message}",
            )
        }
        return TextToolResult.success("Wrote skill '$id'. Load it with load_skill.")
    }

    private suspend fun delete(id: String?): TextToolResult {
        val normalized = id?.trim().orEmpty()
        if (normalized.isBlank()) {
            return TextToolResult.failure(
                code = "MISSING_SKILL_ID",
                message = "delete requires a non-blank id.",
            )
        }
        val validation = RuntimeEnvironment.awaitSettingsGateway().deleteSkill(normalized)
        if (validation != null) {
            return TextToolResult.failure(
                code = "VALIDATION_FAILED",
                message = "${validation.field}: ${validation.message}",
            )
        }
        return TextToolResult.success("Deleted skill '$normalized'.")
    }

    private fun parseArgs(argumentsJson: String): Args {
        val element = try {
            Json.parseToJsonElement(argumentsJson.ifBlank { "{}" })
        } catch (throwable: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.")
        }
        if (element !is JsonObject) {
            throw IllegalArgumentException("argumentsJson must be a JSON object.")
        }
        val overwrite = element["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false
        return Args(
            action = element.stringOrNull("action"),
            id = element.stringOrNull("id"),
            content = element.stringOrNull("content"),
            overwrite = overwrite,
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull
    }

    private data class Args(
        val action: String?,
        val id: String?,
        val content: String?,
        val overwrite: Boolean,
    )

    private companion object {
        const val SCHEMA = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["write", "delete"],
                  "description": "write creates or replaces SKILL.md; delete removes a skill."
                },
                "id": {
                  "type": "string",
                  "description": "Skill id. One or two path segments, no leading slash. Example: wake-windows."
                },
                "content": {
                  "type": "string",
                  "description": "Full SKILL.md text for action=write. Start with --- frontmatter containing name and description."
                },
                "overwrite": {
                  "type": "boolean",
                  "description": "Replace an existing skill. Default false."
                }
              },
              "required": ["action", "id"]
            }
        """
    }
}
