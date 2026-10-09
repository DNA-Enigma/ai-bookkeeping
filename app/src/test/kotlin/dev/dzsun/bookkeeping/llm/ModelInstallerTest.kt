package dev.dzsun.bookkeeping.llm

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置模型释放逻辑的边界。这些都是「静默失效比报错更糟」的地方：
 * 半截模型冒充完整模型、空间不足把盘写满、失败后留 1 GB 垃圾。
 */
class ModelInstallerTest {

    private val targetDir: File = Files.createTempDirectory("model-installer").toFile()

    @After
    fun cleanup() {
        targetDir.deleteRecursively()
    }

    /** 可注入读取失败的资产假件 */
    private class FakeAssets(
        private val data: Map<String, ByteArray>,
        /** 读满这么多字节后抛 IOException；null = 正常读完 */
        private val failAfter: Int? = null,
    ) : ModelAssets {
        var openCount = 0

        override fun names(): List<String> = data.keys.toList()

        override fun sizeOf(name: String): Long = data[name]?.size?.toLong() ?: -1L

        override fun open(name: String): InputStream {
            openCount++
            val bytes = data[name] ?: throw IOException("no such asset: $name")
            return object : ByteArrayInputStream(bytes) {
                // 不能叫 pos：那会遮蔽父类同名字段（编译器警告过）
                private var consumed = 0

                override fun read(): Int {
                    if (failAfter != null && consumed >= failAfter) throw IOException("disk on fire")
                    val b = super.read()
                    if (b >= 0) consumed++
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (failAfter != null && consumed >= failAfter) throw IOException("disk on fire")
                    val n = super.read(b, off, len)
                    if (n > 0) consumed += n
                    return n
                }
            }
        }
    }

    @Test
    fun `空资产包报错而不是静默成功`() {
        val installer = ModelInstaller(FakeAssets(emptyMap()), targetDir)
        val result = installer.ensure()
        assertTrue("应为 Failed，实际 $result", result is ModelInstaller.Result.Failed)
        assertTrue((result as ModelInstaller.Result.Failed).message.contains("内置模型"))
        assertEquals(-1L, installer.expectedBytes())
    }

    @Test
    fun `首次释放写出完整文件并回报进度`() {
        val payload = ByteArray(9 * 1024 * 1024) { (it % 251).toByte() }
        val assets = FakeAssets(mapOf("models/spark.gguf" to payload))
        val installer = ModelInstaller(assets, targetDir)

        val progress = mutableListOf<Float>()
        val result = installer.ensure { progress.add(it) }

        assertTrue("应为 Installed，实际 $result", result is ModelInstaller.Result.Installed)
        val file = (result as ModelInstaller.Result.Installed).file
        assertEquals("spark.gguf", file.name)
        assertEquals(payload.size.toLong(), file.length())
        assertTrue("文件内容与资产不符", payload.contentEquals(file.readBytes()))
        assertEquals(payload.size.toLong(), installer.expectedBytes())

        // 进度：至少有一次中间上报（节流 8 MB），且单调不减、以 1.0 收尾
        assertTrue("进度上报太少：$progress", progress.size >= 2)
        assertEquals(1f, progress.last())
        assertEquals(progress, progress.sorted())
        assertTrue("中间进度应大于 0：$progress", progress.dropLast(1).all { it in 0f..1f && it > 0f })
        assertFalse("不该留下 .part", File(targetDir, "spark.gguf.part").exists())
    }

    @Test
    fun `已有完整模型跳过重写`() {
        val payload = ByteArray(4096) { it.toByte() }
        val assets = FakeAssets(mapOf("models/spark.gguf" to payload))
        val installer = ModelInstaller(assets, targetDir)

        installer.ensure()
        assertEquals(1, assets.openCount)

        val again = installer.ensure()
        assertTrue("应为 AlreadyPresent，实际 $again", again is ModelInstaller.Result.AlreadyPresent)
        assertEquals("第二次不该再读资产", 1, assets.openCount)
    }

    @Test
    fun `尺寸不符的残缺模型被覆盖重装`() {
        val payload = ByteArray(8192) { (it * 3).toByte() }
        val stale = File(targetDir, "spark.gguf")
        stale.writeBytes(ByteArray(100)) // 半截

        val assets = FakeAssets(mapOf("models/spark.gguf" to payload))
        val installer = ModelInstaller(assets, targetDir)

        val result = installer.ensure()
        assertTrue("应为 Installed，实际 $result", result is ModelInstaller.Result.Installed)
        assertEquals(payload.size.toLong(), stale.length())
        assertTrue("覆盖后内容与资产不符", payload.contentEquals(stale.readBytes()))
    }

    @Test
    fun `空间不足时报错且一个字节都不写`() {
        val payload = ByteArray(1024)
        val assets = FakeAssets(mapOf("models/spark.gguf" to payload))
        val installer = ModelInstaller(assets, targetDir, freeSpace = { 10L })

        val result = installer.ensure()
        assertTrue(result is ModelInstaller.Result.Failed)
        assertTrue((result as ModelInstaller.Result.Failed).message.contains("空间不足"))
        assertEquals("目录里不该有东西", 0, targetDir.listFiles()!!.size)
        assertEquals("不该打开资产", 0, assets.openCount)
    }

    @Test
    fun `写到一半失败时不留正式文件也不留垃圾`() {
        val payload = ByteArray(6 * 1024 * 1024) { it.toByte() }
        val assets = FakeAssets(mapOf("models/spark.gguf" to payload), failAfter = 1024 * 1024)
        val installer = ModelInstaller(assets, targetDir)

        val result = installer.ensure()
        assertTrue("应为 Failed，实际 $result", result is ModelInstaller.Result.Failed)
        assertTrue((result as ModelInstaller.Result.Failed).message.contains("写模型失败"))
        assertTrue("不该有半截正式文件", !File(targetDir, "spark.gguf").exists())
        assertTrue("不该留 .part 占空间", !File(targetDir, "spark.gguf.part").exists())
        assertEquals(0, targetDir.listFiles()!!.size)
    }

    @Test
    fun `上次中断留下的 part 会被清掉并重装成功`() {
        File(targetDir, "spark.gguf.part").writeBytes(ByteArray(512))

        val payload = ByteArray(2048) { it.toByte() }
        val assets = FakeAssets(mapOf("models/spark.gguf" to payload))
        val installer = ModelInstaller(assets, targetDir)

        val result = installer.ensure()
        assertTrue("应为 Installed，实际 $result", result is ModelInstaller.Result.Installed)
        assertEquals(payload.size.toLong(), File(targetDir, "spark.gguf").length())
        assertFalse(File(targetDir, "spark.gguf.part").exists())
    }

    @Test
    fun `没有 gguf 只有别的资产时不乱动`() {
        val assets = FakeAssets(mapOf("models/readme.txt" to "hi".toByteArray()))
        val installer = ModelInstaller(assets, targetDir)

        val result = installer.ensure()
        assertTrue(result is ModelInstaller.Result.Failed)
        assertEquals(0, targetDir.listFiles()!!.size)
    }
}
