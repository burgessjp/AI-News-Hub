package com.peng.ainewshub.ui

import com.peng.ainewshub.data.AppException
import com.peng.ainewshub.data.ShortContentException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

/**
 * [toUiError] 异常分类映射回归:每类 data 层异常必须落到正确的 ErrorKind
 * (UI 据此切文案/图标),文案经资源取出非空。
 */
@RunWith(RobolectricTestRunner::class)
class UiErrorMappingTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun kindOf(t: Throwable): Pair<ErrorKind, String> {
        val error = t.toUiError(context) as UiState.Error
        return error.kind to error.message
    }

    @Test
    fun `AppException 各子类映射到对应 ErrorKind`() {
        assertEquals(ErrorKind.NoData, kindOf(AppException.NoData()).first)
        assertEquals(ErrorKind.Network, kindOf(AppException.Network()).first)
        assertEquals(ErrorKind.ServerError, kindOf(AppException.ServerError()).first)
        assertEquals(ErrorKind.AiService, kindOf(AppException.AiService()).first)
        assertEquals(ErrorKind.AiAuth, kindOf(AppException.AiAuth()).first)
        assertEquals(ErrorKind.RateLimited, kindOf(AppException.RateLimited()).first)
    }

    @Test
    fun `IO 与未知异常分别归 Network 与 Unknown`() {
        assertEquals(ErrorKind.Network, kindOf(IOException("connection reset")).first)
        assertEquals(ErrorKind.Unknown, kindOf(IllegalStateException("boom")).first)
        // 翻译原文过短走 TOO_SHORT 语义,kind 不参与分支
        assertEquals(ErrorKind.Unknown, kindOf(ShortContentException()).first)
    }

    @Test
    fun `所有文案均非空技术词`() {
        listOf(
            AppException.NoData(), AppException.Network(), AppException.ServerError(),
            AppException.AiService(), AppException.AiAuth(), AppException.RateLimited(),
            IOException("e"), RuntimeException("e")
        ).forEach { t ->
            val message = kindOf(t).second
            assertTrue("文案为空: ${t::class.simpleName}", message.isNotBlank())
        }
    }
}
