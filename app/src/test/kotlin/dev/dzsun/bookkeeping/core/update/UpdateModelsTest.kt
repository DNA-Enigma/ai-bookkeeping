package dev.dzsun.bookkeeping.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateModelsTest {

    @Test
    fun `版本更高才算有更新`() {
        assertTrue(isNewerVersion(remoteVersionCode = 5, localVersionCode = 4))
        assertFalse(isNewerVersion(remoteVersionCode = 4, localVersionCode = 4))
    }

    @Test
    fun `版本更低不算更新`() {
        // 用户装了更新的版本（比如从别处侧载）时，不能被旧清单"降级"回去
        assertFalse(isNewerVersion(remoteVersionCode = 3, localVersionCode = 4))
    }

    @Test
    fun `跨大版本仍然按数值比较`() {
        assertTrue(isNewerVersion(remoteVersionCode = 100, localVersionCode = 99))
        // 字符串比较会把 "9" 排在 "10" 之后，数值比较不会
        assertTrue(isNewerVersion(remoteVersionCode = 10, localVersionCode = 9))
    }
}
