package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import com.niki914.zafiro.chat.agentic.buildin.TextToolResultCodec
import com.niki914.zafiro.chat.agentic.buildin.impl.SkillMetaBuiltin
import com.niki914.zafiro.settings.RuntimeEnvironment
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillMetaBuiltinTest {
    @After
    fun tearDown() {
        RuntimeEnvironment.clearForTest()
    }

    @Test
    fun write_storesContentAndRefusesOverwrite() = runTest {
        val gateway = installRuntimeSettingsGatewayForTest()
        val content = "---\nname: Wake\ndescription: wake a pc\n---\nsteps"

        val created = invoke("""{"action":"write","id":"wake-windows","content":${jsonString(content)}}""")

        assertEquals(TextToolResult.Status.Success, created.status)
        assertEquals(content, gateway.writtenSkills["wake-windows"])

        val refused = invoke("""{"action":"write","id":"wake-windows","content":"next"}""")

        assertEquals(TextToolResult.Status.Failure, refused.status)
        assertEquals("VALIDATION_FAILED", refused.code)
        assertEquals(content, gateway.writtenSkills["wake-windows"])
    }

    @Test
    fun delete_removesWrittenSkill() = runTest {
        val gateway = installRuntimeSettingsGatewayForTest()
        invoke("""{"action":"write","id":"wake-windows","content":"body"}""")

        val deleted = invoke("""{"action":"delete","id":"wake-windows"}""")

        assertEquals(TextToolResult.Status.Success, deleted.status)
        assertTrue(gateway.writtenSkills.isEmpty())
    }

    @Test
    fun write_blankContentFailsBeforeGateway() = runTest {
        val gateway = installRuntimeSettingsGatewayForTest()

        val result = invoke("""{"action":"write","id":"wake-windows","content":"  "}""")

        assertEquals("MISSING_CONTENT", result.code)
        assertTrue(gateway.writtenSkills.isEmpty())
    }

    private suspend fun invoke(argumentsJson: String): TextToolResult {
        val raw = SkillMetaBuiltin().invokeRawJson(
            BuiltinToolRequest(name = "skill_meta", argumentsJson = argumentsJson)
        )
        return TextToolResultCodec.decode(raw)!!
    }

    private fun jsonString(value: String): String {
        return buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    else -> append(char)
                }
            }
            append('"')
        }
    }
}
