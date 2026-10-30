package com.livehub.tv.core.model

import com.livehub.tv.ui.components.qualityLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * core 的 qn 表与 ui 的上屏名之间，只允许一种不一致：**ui 少说**（不写分辨率）。
 *
 * 为什么这条要写成可跑的单测而不是文档里的一句话：2026-10-29 前一天，`ui/components/Cards.kt`
 * 写着 `"超清" -> "超清 1080P"`，而平台 `g_qn_desc` 说 250 是 **720P**。当时它是**死分支**
 * （上游把 250 翻成「高清」，先被 `contains("高清")` 截走），所以一年多没人看见；
 * 上游改用 `bilibiliQuality(qn)` 之后那条分支当场复活，屏幕上开始把 720P 报成 1080P。
 *
 * 教训是：**档位名对了不等于画质诚实**。名字是平台起的，分辨率是事实，两者各自都会说谎：
 * - 名字错、分辨率对（`超清` 被写成 `高清 720P`）——用户看到档次差一档；
 * - 名字错、根本不写分辨率（`高清` 被写成 `流畅`）——用户看到档次差一档，且无从核对；
 * - 名字对、分辨率错（`超清 1080P`）——用户看不出来，这就是上面那个真事。
 *
 * 所以第一版这条测试只比分辨率是**不够的**（两个洞，本节记着）：所有档位名都不比，
 * 且没写分辨率的档位被 `continue` 掉。现在**名字永远比**，分辨率只在 core 有值时才比。
 *
 * 反过来的不自由也允许：ui 不写分辨率（只说「高清」）不算谎，只是信息少。
 * 这条测试**不**要求 ui 必须写出分辨率，否则它就会逼着 ui 再抄一份 core 的表。
 */
class QualityNameConsistencyTest {

    /** 平台会签发的档 + 一个未知码（未知码也不能被 ui 编出分辨率）。 */
    private val qns = listOf(80, 150, 250, 400, 10000, 15000, 20000, 30000, 9999)

    /**
     * 串尾的分辨率声明。**必须锚尾**：不锚的话 `2K 极清` 会先命中标签里的 `2K`，
     * 拿标签去比分辨率（下一批 ui 改走 `display` 后 15000 会输出 `2K 极清 2K`）。
     * `[248]K` 这一支是为 `2K/4K/8K` 准备的——`\d{3,4}[Pp]` 抓不到它们。
     */
    private val trailingResolution = Regex("""(\d{3,4}[Pp]|[248]K)$""")

    /**
     * 判据本体：ui 这句话和 core 对这一档的结论是否打架。返回「这句里有没有分辨率声明」。
     *
     * 为什么「剩下那截尾巴才算分辨率」要先确认 core 的名字是**前缀**：
     * `qn=20000` 的标签本身就叫 `4K`，而 `"4K".endsWith("4K")` 是真。只看正则的话，
     * 标签会被当成分辨率声明，再和 core 的空 `resolution` 一比 → **假红**。
     * 这条不是理论风险，是有人手推时算漏了（见 [theRegexAloneWouldFalseRedOnThe4KLabel]）。
     */
    private fun assertUiAgreesWithCore(qn: Int, shown: String): Boolean {
        val core = bilibiliQuality(qn)
        val claimed = if (shown.length > core.label.length && shown.startsWith(core.label))
            trailingResolution.find(shown)?.value else null
        val name = if (claimed != null) shown.removeSuffix(claimed).trim() else shown.trim()

        // 名字永远要比：档位名也是我们对用户说的话，错名字和错分辨率是同一类谎。
        assertEquals(
            "qn=$qn 的档位名：ui 上屏「$name」，core 是「${core.label}」——" +
                "名字错了，用户看到的就是另一档画质",
            core.label,
            name
        )
        if (claimed != null) {
            assertEquals(
                "qn=$qn 的分辨率：ui 上屏「$claimed」，core 说「${core.resolution}」——" +
                    "名字对、分辨率错，用户在屏幕上看不出来",
                core.resolution.lowercase(),
                claimed.lowercase()
            )
        }
        return claimed != null
    }

    @Test
    fun uiNeverClaimsAResolutionCoreDisagreesWith() {
        var claims = 0
        for (qn in qns) {
            if (assertUiAgreesWithCore(qn, qualityLabel(bilibiliQuality(qn).label))) claims++
        }
        // 没有这一行，上面整条测试可能是恒真的：ui 若哪天连分辨率都不写了，
        // 正则一次都不匹配，测试照样绿。**绿要能被证明不是空转。**
        // 下界而不是 `== 1`：下一批 ui 改走 `display` 后会有好几档带分辨率。
        assertTrue("ui 至少在一个档位上写了分辨率，否则分辨率那半截判据什么都没验", claims >= 1)
    }

    /**
     * 负对照：把上一版那个错的字符串喂进**同一套判据**，必须判出不一致。
     *
     * 这条不测产品代码，它测的是「上面那条测试有没有牙」。判据自己恒真时，
     * 测试比你删掉它更危险——它会替一个谎盖章。
     */
    @Test
    fun theComparatorItselfWouldHaveCaughtTheOldString() {
        val oldShown = "超清 1080P"
        val claimed = trailingResolution.find(oldShown)?.value
        assertTrue("正则抓不到「1080P」，说明判据坏了，上面那条测试等于没写", claimed != null)
        assertNotEquals(
            "core 说 250 是 720P，旧上屏名说 1080P——判据必须能判出这个不一致",
            bilibiliQuality(250).resolution.lowercase(),
            claimed!!.lowercase()
        )
    }

    /**
     * 洞 A（reviewer 2026-10-29 指出）：**名字错、分辨率对**。
     *
     * 旧判据只比分辨率，`高清 720P` 拿去喂 qn=250 会全绿——可用户看到的是「高清」，
     * 而平台给的这一档叫「超清」。差一档，且这种错最容易发生：名字手抄、数字从下表算。
     */
    @Test
    fun theComparatorCatchesAWrongNameEvenWhenTheResolutionIsRight() {
        val e = assertThrows(AssertionError::class.java) { assertUiAgreesWithCore(250, "高清 720P") }
        assertTrue("报错信息要能指出是名字错，否则下一个读日志的人还得自己猜", e.message!!.contains("超清"))
    }

    /**
     * 洞 B（同一条 review）：**名字错、且根本不写分辨率**。
     *
     * 旧判据在「正则没匹配上」时 `continue`，等于「不写分辨率就不检查」——
     * 于是 `qn=150` 被上屏成「流畅」（低报一档，正是旧 `qnName()` 干过的事）也全绿。
     */
    @Test
    fun theComparatorCatchesAWrongNameThatClaimsNoResolution() {
        val e = assertThrows(AssertionError::class.java) { assertUiAgreesWithCore(150, "流畅") }
        assertTrue("报错信息要能指出是名字错", e.message!!.contains("高清"))
    }

    /**
     * 加固代码本身的反面对照：**只靠正则、不看标签前缀，qn=20000 会当场假红。**
     *
     * 这一条是给「下一批换签名」准备的：那天 ui 的串由 `display` 模板拼出来，
     * 名字里带 `2K/4K` 的档位会自己撞上 `[248]K$`。判据必须先确认 core 的名字是前缀，
     * 再把剩下的尾巴当分辨率——否则第一次跑就是一片红，而红的原因不是产品错了。
     */
    @Test
    fun theRegexAloneWouldFalseRedOnThe4KLabel() {
        val core = bilibiliQuality(20000)
        assertEquals("4K", core.label)
        assertEquals("core 说 4K 这一档没有分辨率（平台 g_qn_desc 的 brief_desc 是空的）", "", core.resolution)
        assertEquals(
            "正则在「4K」这个**标签本身**上就会命中——这就是前缀条件的由来",
            "4K",
            trailingResolution.find(core.label)?.value
        )
    }

    /** 上一轮那个具体 bug：250 是 720P，不是 1080P。把它钉死，别让死分支再复活一次。 */
    @Test
    fun superClearIsNeverReportedAs1080p() {
        val shown = qualityLabel(bilibiliQuality(250).label)
        assertEquals("超清 720P", shown)
        assertNotEquals("250 是 720P，1080P 只有 400 和 10000", "超清 1080P", shown)
    }

    /** 未知码：core 写 `qn<码>`，ui 不许给它编一个档位名或分辨率。 */
    @Test
    fun unknownCodeGetsNoInventedResolution() {
        val core = bilibiliQuality(9999)
        assertEquals("qn9999", core.label)
        assertEquals("未知码没有分辨率，ui 也不许补", core.label, qualityLabel(core.label))
    }
}
