package dev.dzsun.bookkeeping.feature.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预算的落盘格式。
 *
 * 编解码出错的后果是**静默丢预算**——用户设好了，重启后一片空白，
 * 而他不会知道是自己没设成功还是应用把它弄丢了。所以往返必须逐字对得上。
 */
class BudgetCodecTest {

    @Test
    fun `空输入往返为空`() {
        assertEquals(emptyList<CategoryBudget>(), BudgetCodec.decode(null))
        assertEquals(emptyList<CategoryBudget>(), BudgetCodec.decode(""))
        assertEquals(emptyList<CategoryBudget>(), BudgetCodec.decode("   "))
        assertEquals("[]", BudgetCodec.encode(emptyList()))
    }

    @Test
    fun `往返之后一模一样`() {
        val budgets = listOf(
            CategoryBudget("expense.food", 110_000L),
            CategoryBudget("expense.transport", 30_000L),
        )
        assertEquals(budgets, BudgetCodec.decode(BudgetCodec.encode(budgets)))
    }

    @Test
    fun `分类 id 里带分隔符也不会撑破格式`() {
        // 分类是数据不是代码，用户自建的 id 什么形状都可能有。
        // 自己拼分隔符的写法会在这种 id 上悄悄吃掉一条预算
        val budgets = listOf(CategoryBudget("expense.custom|weird=id", 1L))
        assertEquals(budgets, BudgetCodec.decode(BudgetCodec.encode(budgets)))
    }

    @Test
    fun `解析不了的内容按空处理，不抛异常`() {
        // prefs 被外部写坏时，让每个读预算的页面都崩掉比显示"还没设预算"更糟
        assertEquals(emptyList<CategoryBudget>(), BudgetCodec.decode("{不是 JSON"))
        assertEquals(emptyList<CategoryBudget>(), BudgetCodec.decode("[{\"categoryId\":1}]"))
    }

    @Test
    fun `非正金额与空 id 在读取时被丢掉`() {
        val raw = BudgetCodec.encode(
            listOf(
                CategoryBudget("expense.food", 0L),
                CategoryBudget("expense.transport", -5L),
                CategoryBudget("", 100L),
                CategoryBudget("expense.shopping", 200L),
            ),
        )
        assertEquals(listOf(CategoryBudget("expense.shopping", 200L)), BudgetCodec.decode(raw))
    }

    @Test
    fun `同一分类写了多条时以最后一条为准`() {
        val raw = """[{"categoryId":"expense.food","amountMinor":100},""" +
            """{"categoryId":"expense.food","amountMinor":900}]"""
        // 读的时候不该出现两条互相打架的预算——界面会按第一条画，用户看到的就不是他最后设的数
        assertEquals(listOf(CategoryBudget("expense.food", 900L)), BudgetCodec.decode(raw))
    }

    @Test
    fun `未知字段不会让整份预算读不出来`() {
        // 以后加了字段又回退版本时，别把用户设好的预算全丢了
        val raw = """[{"categoryId":"expense.food","amountMinor":100,"currency":"CNY"}]"""
        assertEquals(listOf(CategoryBudget("expense.food", 100L)), BudgetCodec.decode(raw))
    }

    @Test
    fun `读出来的顺序是稳定的`() {
        val raw = BudgetCodec.encode(
            listOf(CategoryBudget("expense.transport", 1L), CategoryBudget("expense.food", 2L)),
        )
        assertEquals(
            listOf("expense.food", "expense.transport"),
            BudgetCodec.decode(raw).map { it.categoryId },
        )
        assertTrue(BudgetCodec.decode(raw).isNotEmpty())
    }
}
