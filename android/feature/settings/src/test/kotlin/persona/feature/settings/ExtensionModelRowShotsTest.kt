package persona.feature.settings

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import persona.core.contracts.JsonCodec
import persona.core.contracts.api.*
import persona.core.designsystem.AppStyle
import persona.core.designsystem.PersonaTheme
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-w400dp-h1100dp-xhdpi")
class ExtensionModelRowShotsTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun modelPurposeDeepLinkAndExtensionModelRowRenderAndNavigate() {
        val url = "persona://open/settings?tab=models&item=extension.persona-phone"
        assertEquals("extension.persona-phone", ExtensionViewText.modelPurpose(url))
        assertTrue(ExtensionViewText.opens("persona-phone", url))

        val detail = ExtensionDetail(
            extension = HubExtension(
                id = "persona-phone",
                name = "Persona 手机操控",
                kind = "connectors",
                status = "running",
                source = "installed",
                enabled = true,
                version = "0.3.3",
                description = "远程操控已配对的安卓手机，支持后台虚拟屏运行、小屏预览与通知验证码自动登录，敏感与支付操作保留手机端确认。",
            ),
            ui = listOf(ExtensionUiPlace("persona-phone", "persona-phone", "手机操控控制台", "extension_page")),
            form = listOf(
                ExtensionField("default_wait_seconds", "integer", title = "默认等待秒数", description = "等待任务状态变更的默认超时秒数（1–50，推荐 25）。"),
                ExtensionField("require_confirmation", "boolean", title = "写操作需要 Persona 确认卡", description = "开启后，下发或修改手机任务前会弹出确认卡；关闭后直接执行（手机端支付与验证码门禁仍然生效）。"),
                ExtensionField("preferred_models", "string", title = "优先模型（复用 Hub 供应商）", description = "可选：填写 Hub 已配置供应商中的模型 ID（多个用逗号分隔），按顺序自动匹配；留空则使用「设置 › 模型配置」中为本扩展选定的模型。"),
            ),
            config = buildJsonObject {
                put("default_wait_seconds", 25)
                put("require_confirmation", false)
                put("preferred_models", "")
            },
        )
        val extApi = object : ExtensionApiStub() {
            override suspend fun extension(id: String) = detail
        }
        val catalog = JsonCodec.decodeFromString<ModelCatalog>(
            """
            {
              "providers": [
                {"id": "launcher.opencode", "name": "opencode", "kind": "api", "model": "deepseek-v4.1-flash"}
              ],
              "purposes": [
                {
                  "id": "extension.persona-phone",
                  "name": "Persona 手机操控",
                  "group": "扩展",
                  "kind": "api",
                  "effective": {"provider": "launcher.opencode", "model": "deepseek-v4.1-flash", "effort": "off"},
                  "fields": ["provider", "model", "effort"],
                  "provider_ids": ["launcher.opencode"]
                }
              ]
            }
            """.trimIndent()
        )
        val modelsApi = object : ModelConfiguration {
            override suspend fun catalog() = catalog
            override suspend fun purpose(id: String): ModelPurposeDetail = error("not used")
            override suspend fun save(purpose: ModelPurpose, draft: ModelConfigurationDraft): ModelPurposeSave = ModelPurposeSave.Conflict
            override suspend fun providerModels(provider: String, refresh: Boolean, machine: String?): ProviderModels? = null
        }

        var openedPurpose: String? = null
        rule.setContent {
            PersonaTheme(style = AppStyle.Brief, darkTheme = false, reduceMotion = true) {
                Box(Modifier.testTag("shot").fillMaxSize().background(PersonaTheme.palette.background)) {
                    ExtensionPage(
                        extensions = extApi,
                        extensionId = "persona-phone",
                        onBack = {},
                        onPage = {},
                        models = modelsApi,
                        onModel = { purposeId, _ -> openedPurpose = purposeId },
                    )
                }
            }
        }
        rule.waitUntil(5_000L) { rule.onAllNodesWithText("使用的模型").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("使用的模型").assertExists()

        val outDir = File(System.getProperty("persona.shots") ?: "/tmp/build21-shots").also { it.mkdirs() }
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        File(outDir, "extension-phone-model.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        rule.onNodeWithText("使用的模型").performClick()
        rule.waitForIdle()
        assertEquals("extension.persona-phone", openedPurpose)
    }
}
