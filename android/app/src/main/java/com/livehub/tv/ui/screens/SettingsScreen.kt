package com.livehub.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livehub.tv.core.store.Prefs
import com.livehub.tv.ui.AppNav
import com.livehub.tv.ui.Screen
import com.livehub.tv.ui.components.GlassBox
import com.livehub.tv.ui.components.PageHead
import com.livehub.tv.ui.components.PillButton
import com.livehub.tv.ui.components.SegmentedVisual
import com.livehub.tv.ui.components.SwitchVisual
import com.livehub.tv.ui.components.Tier
import com.livehub.tv.ui.components.TvFocus
import com.livehub.tv.ui.components.focusScale
import com.livehub.tv.ui.components.tryRequestFocus
import com.livehub.tv.ui.components.upTo
import com.livehub.tv.ui.components.rememberTvFocus
import com.livehub.tv.ui.components.tvClickable
import com.livehub.tv.ui.theme.LiveHubType
import com.livehub.tv.ui.theme.Tokens

private val GROUP_NAMES = listOf("播放", "弹幕", "账号与同步", "关于")

/**
 * 「关键词屏蔽」开着时写进 Prefs 的词表。
 *
 * 为什么不是让用户在本机编辑字符串：电视上没有舒服的文本输入，为一个逗号分隔的自由文本
 * 拉出一块软键盘，代价远大于收益。所以这里只做「开/关」两态，词表本身写死在代码里、
 * 显式写在描述里给用户看——可预期比可编辑更重要，等有输入方案再开放编辑。
 */
private const val DEFAULT_SHIELD = "广告,加群,私信,代练,刷单,加微信"

/**
 * 一行设置。
 *
 * [options] 为空表示开关；否则是分段控件。点击一行就切换（开关翻转、分段往后选一格）。
 * [read] 读当前值，[write] 写回——**有 Prefs 字段的一律真读写**。
 * [disabled] 为 true 表示本版没有实现路径：不参与焦点、灰态、右侧给一句为什么。
 * 这一类绝不能做成「可点但没反应」——用户调了发现没变化，会以为软件坏了。
 */
private class SettingSpec(
    val label: String,
    val desc: String,
    val options: List<String> = emptyList(),
    val read: () -> Int,
    val write: (Int) -> Unit,
    val disabled: Boolean = false,
    val note: String? = null
)

/**
 * 设置页：左栏分组钉死，右侧面板自己滚。
 *
 * 两栏必须用同一套焦点语言（同一块玻璃、同样的 1.02、同一道顶沿高光）；
 * 只把右侧底色换成 --raised 会立刻变成网页的 hover。
 * 默认焦点落在第一项设置上——进设置页是想改设置，不是改分类。
 */
@Composable
fun SettingsScreen(nav: AppNav, modifier: Modifier = Modifier) {
    val groupIndex = nav.settingsGroup.coerceIn(0, GROUP_NAMES.lastIndex)
    val specs = remember(groupIndex) { specsOf(groupIndex) }
    val local = remember { mutableStateMapOf<String, Int>() }
    val scroll = rememberScrollState()
    val firstRow = rememberTvFocus(nav.settingsFirstRow)

    // 首行如果是灰态就落到第一个能调的项上——焦点不该停在一个没反应的开关上
    val firstEnabledIndex = remember(specs) { specs.indexOfFirst { !it.disabled } }

    LaunchedEffect(groupIndex) {
        // 换分组必须把面板滚回顶：面板是自己滚的，上一层滚到一半切过来会从莫名其妙的位置开始
        scroll.scrollTo(0)
        // 顶栏拿着焦点时不抢（切屏的同一次按键会被刚抢到的行吃掉）
        if (nav.inNav) return@LaunchedEffect
        // 整组都是灰置项（账号与同步/关于）时 firstRow 从未 attach，抢焦点会落空；
        // 让焦点留在左栏分组上，用户仍然能继续导航。
        if (firstEnabledIndex < 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstRow.requester.requestFocus() }
    }

    val tabRequesters = nav.settingsTabs
    val decoderLabel = listOf("自动", "硬件", "软件")[Prefs.decoderMode.coerceIn(0, 2)]
    val qualityText = when (Prefs.preferredQuality) {
        "150" -> "高清 150"
        "250" -> "超清 250"
        "400" -> "蓝光 400"
        else -> "自动（最高）"
    }
    val shieldText = if (Prefs.danmakuKeywords.isBlank()) "关" else "开"

    Column(modifier.fillMaxSize()) {
        PageHead(
            title = "设置",
            subtitle = "默认值已经调好，不改也能用。"
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 36.dp)
        ) {
            // ---------- 左：只读状态卡（设计稿 §8.1） ----------
            Box(
                modifier = Modifier
                    .width(490.dp)
                    .padding(start = Tokens.Gutter)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Tokens.Panel)
                    .padding(34.dp)
            ) {
                Column {
                    Text(text = "当前播放", style = LiveHubType.small, color = Tokens.Faint)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "$decoderLabel 解码",
                        style = LiveHubType.h2,
                        color = Tokens.Text
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "线路降级${if (Prefs.autoDowngrade) "已开启" else "已关闭"} · 起播缓冲 1 秒",
                        style = LiveHubType.small,
                        color = Tokens.Muted
                    )
                    Spacer(Modifier.height(26.dp))
                    StatusLine("默认画质", qualityText)
                    StatusLine("解码方式", decoderLabel)
                    StatusLine("线路自动降级", if (Prefs.autoDowngrade) "开" else "关")
                    StatusLine("关键词屏蔽", shieldText)
                    Spacer(Modifier.height(24.dp))
                    val firstMs = Prefs.lastFirstFrameMs
                    val drops = Prefs.downgrades7d()
                    val dropText = if (drops == 0) {
                        "过去 7 天没有触发过降级。"
                    } else {
                        "过去 7 天触发了 $drops 次降级。"
                    }
                    Text(
                        text = if (firstMs > 0) {
                            "首帧 ${String.format(java.util.Locale.CHINA, "%.1f", firstMs / 1000.0)} 秒。$dropText"
                        } else {
                            "还没成功起播过。$dropText"
                        },
                        style = LiveHubType.small,
                        color = Tokens.Faint
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = "LiveHub v0.1.0",
                        style = LiveHubType.small.copy(fontSize = 18.sp),
                        color = Color(0xFF5A5A5E)
                    )
                    Text(
                        text = "仅观看各平台公开直播内容",
                        style = LiveHubType.small.copy(fontSize = 18.sp),
                        color = Color(0xFF5A5A5E)
                    )
                }
            }

            Spacer(Modifier.width(48.dp))

            // ---------- 右：横向分组标签 + 设置面板 ----------
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = Tokens.Gutter, bottom = 36.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    GROUP_NAMES.forEachIndexed { index, name ->
                        val tabTv = rememberTvFocus(tabRequesters[index])
                        PillButton(
                            text = name,
                            onClick = { nav.settingsGroup = index },
                            solid = groupIndex == index,
                            tv = tabTv,
                            height = 56.dp,
                            horizontalPadding = 24.dp,
                            fontSize = 24.sp,
                            modifier = Modifier
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) {
                                        return@onPreviewKeyEvent false
                                    }
                                    when (event.key) {
                                        Key.DirectionLeft ->
                                            tabRequesters.getOrNull(index - 1)
                                                ?.tryRequestFocus() ?: true
                                        Key.DirectionRight ->
                                            tabRequesters.getOrNull(index + 1)
                                                ?.tryRequestFocus() ?: true
                                        Key.DirectionUp ->
                                            nav.tabRequests[Screen.Settings.ordinal]
                                                .tryRequestFocus()
                                        Key.DirectionDown ->
                                            if (firstEnabledIndex >= 0) {
                                                firstRow.requester.tryRequestFocus()
                                            } else {
                                                false
                                            }
                                        else -> false
                                    }
                                }
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Tokens.Panel)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            // 底部 96dp：最后一项要能滚到渐隐之上、整行露出。
                            .padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 96.dp)
                    ) {
                        specs.forEachIndexed { index, spec ->
                            val value = local[spec.label] ?: spec.read()
                            SettingRow(
                                spec = spec,
                                value = value,
                                tv = if (index == firstEnabledIndex) firstRow else rememberTvFocus(),
                                // 第一项往上回本组标签；标签再往上才回「设置」页签
                                upTo = if (index == firstEnabledIndex) tabRequesters[groupIndex] else null,
                                onClick = {
                                    val next = if (spec.options.isEmpty()) {
                                        if (value == 0) 1 else 0
                                    } else {
                                        (value + 1) % spec.options.size
                                    }
                                    local[spec.label] = next
                                    spec.write(next)
                                }
                            )
                        }
                        Text(
                            text = settingsNote(groupIndex),
                            style = LiveHubType.small,
                            color = Tokens.Faint,
                            modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp)
                        )
                    }
                    // 下沿渐隐：列表没滚到底时，最后一行不该像被刀切过——它该「隐进」面板里。
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(88.dp)
                            .background(
                                Brush.verticalGradient(
                                    0f to Tokens.Panel.copy(alpha = 0f),
                                    1f to Tokens.Panel
                                )
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
    ) {
        Text(text = label, style = LiveHubType.small, color = Tokens.Muted, modifier = Modifier.weight(1f))
        Text(text = value, style = LiveHubType.small, color = Tokens.Text)
    }
}

@Composable
private fun SettingRow(
    spec: SettingSpec,
    value: Int,
    onClick: () -> Unit,
    tv: TvFocus = rememberTvFocus(),
    upTo: FocusRequester? = null
) {
    // 灰态：不挂 tvClickable（焦点系统直接跳过），右侧给一句为什么调不了。
    // 做成一整行 40% 透明的「假开关」是最糟的，用户会一直按。
    if (spec.disabled) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = spec.label,
                    style = LiveHubType.title.copy(fontSize = 26.sp, fontWeight = FontWeight.Medium),
                    color = Tokens.Faint
                )
                Spacer(Modifier.height(6.dp))
                Text(text = spec.desc, style = LiveHubType.small, color = Color(0xFF4A4A4E))
            }
            Spacer(Modifier.width(24.dp))
            Text(
                text = spec.note ?: "未实现",
                style = LiveHubType.small,
                color = Color(0xFF5A5A5E)
            )
        }
        return
    }

    val scale = focusScale(tv, Tier.ROW_SCALE)
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .upTo(upTo)
            .tvClickable(tv, onClick = onClick)
    ) {
        GlassBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp),
            radius = 16.dp,
            focused = tv.focused,
            baseColor = Color.Transparent,
            focusedColor = Tokens.Glass
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = spec.label,
                        style = LiveHubType.title.copy(fontSize = 26.sp, fontWeight = FontWeight.Medium),
                        color = Tokens.Text
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = spec.desc,
                        style = LiveHubType.small,
                        color = if (tv.focused) Tokens.Muted else Tokens.Faint
                    )
                }
                Spacer(Modifier.width(24.dp))
                if (spec.options.isEmpty()) {
                    SwitchVisual(checked = value == 1)
                } else {
                    SegmentedVisual(options = spec.options, selectedIndex = value)
                }
            }
        }
    }
}

/**
 * 面板底部的静态说明，按分组给不同的一句。
 *
 * 「关于」这一句是有用的：App 保留了 `usesCleartextTraffic`，因为斗鱼的 hdl 和部分
 * CDN 的 FLV 没全量上 TLS。用户看到地址栏没有 https 锁标时，得有个地方能查到原因——
 * 这不是免责声明，是回答问题。
 */
private fun settingsNote(group: Int): String = when (group) {
    2 -> "这一屏只影响本机。当前版本四个平台都不登录，所以没有账号可同步。"
    3 -> "部分直播源为 HTTP 明文传输：斗鱼的 hdl 地址与部分 CDN 的 FLV 流没有全量上 TLS，因此应用保留了明文流量许可。"
    else -> "这一屏只影响本机，不改动各平台的账号与内容。"
}

/**
 * 每一组的定义。分三类，界线就是「按下去之后有没有东西真的变了」：
 *
 * 1. **真落盘**：Prefs 里本来就有字段的（解码方式、默认画质、弹幕开关/字号/不透明度），
 *    read/write 直接读写 Prefs。
 * 2. **新字段落盘**：本轮补上存储位的（线路自动降级、同屏密度上限、关键词屏蔽、显示区域），
 *    字段在 Prefs 里，消费方（`PlayerScreen` / `DanmakuLayer`）已经真的读它。
 * 3. **灰态**：v1 没有实现路径的（起播缓冲、开播提醒、账号与同步整组、HDR、检查更新…）。
 *    这类 `disabled = true`，不挂 `tvClickable`、不进焦点链，右侧写一句为什么调不了。
 *
 * 第三条是本文件最容易写歪的地方：摆一个能点的开关但它只在内存里翻，用户调完发现画面
 * 没变，会认为软件坏了——给不出效果的地方，就要明说给不出。
 */
private fun specsOf(group: Int): List<SettingSpec> = when (group) {
    0 -> listOf(
        SettingSpec(
            label = "解码方式",
            desc = "硬件解码更省电，遇到花屏再切软件",
            options = listOf("自动", "硬件", "软件"),
            read = { Prefs.decoderMode },
            write = { Prefs.decoderMode = it }
        ),
        SettingSpec(
            label = "默认画质",
            desc = "自动 = 向平台要最高档；平台压档时以播放页显示的为准",
            options = listOf("自动（最高）", "高清 150", "超清 250", "蓝光 400"),
            read = {
                when (Prefs.preferredQuality) {
                    "150" -> 1
                    "250" -> 2
                    "400" -> 3
                    else -> 0
                }
            },
            write = {
                // 存平台自己的 qn 数字，不存「原画」这种名字：
                // 哔哩未登录会被压到 250，我们能诚实地要 250，但不能假装给了原画
                Prefs.preferredQuality = when (it) {
                    1 -> "150"
                    2 -> "250"
                    3 -> "400"
                    else -> ""
                }
            }
        ),
        SettingSpec(
            label = "线路自动降级",
            desc = "3 秒没出首帧先提示，4 秒才切备用线路；关掉只提示不切",
            read = { if (Prefs.autoDowngrade) 1 else 0 },
            write = { Prefs.autoDowngrade = it == 1 }
        ),
        SettingSpec(
            label = "起播缓冲",
            desc = "越大越稳，换台越慢",
            options = listOf("0.5 秒", "1 秒", "2 秒"),
            read = { 1 },
            write = {},
            disabled = true,
            note = "v2"
        ),
        SettingSpec(
            label = "开播提醒",
            desc = "关注的主播开播时，首页主图优先展示",
            read = { 0 },
            write = {},
            disabled = true,
            note = "v2"
        ),
        SettingSpec(
            label = "开机回到上次频道",
            desc = "开机直接进上次看的台，不从首页起",
            read = { 0 },
            write = {},
            disabled = true,
            note = "v2"
        ),
        SettingSpec(
            label = "HDR 优先",
            desc = "片源支持时优先 HDR，画面更亮但更费电",
            read = { 0 },
            write = {},
            disabled = true,
            note = "v2"
        )
    )

    1 -> listOf(
        SettingSpec(
            label = "默认开启",
            desc = "进入直播间自动显示弹幕",
            read = { if (Prefs.playerDanmakuDefault) 1 else 0 },
            write = { Prefs.playerDanmakuDefault = it == 1 }
        ),
        SettingSpec(
            label = "字号",
            desc = "大屏建议 40px 以上",
            options = listOf("32", "40", "48"),
            read = {
                when (Prefs.danmakuSize) {
                    32 -> 0
                    48 -> 2
                    else -> 1
                }
            },
            write = {
                Prefs.danmakuSize = when (it) {
                    0 -> 32
                    2 -> 48
                    else -> 40
                }
            }
        ),
        SettingSpec(
            label = "同屏密度上限",
            desc = "超出直接丢弃，不排队",
            options = listOf("8 条", "12 条", "20 条"),
            read = {
                when {
                    Prefs.danmakuMaxOnScreen <= 8 -> 0
                    Prefs.danmakuMaxOnScreen >= 20 -> 2
                    else -> 1
                }
            },
            write = {
                Prefs.danmakuMaxOnScreen = when (it) {
                    0 -> 8
                    2 -> 20
                    else -> 12
                }
            }
        ),
        SettingSpec(
            label = "关键词屏蔽",
            desc = "开启后按固定词表过滤：广告 / 加群 / 私信等",
            read = { if (Prefs.danmakuKeywords.isBlank()) 0 else 1 },
            write = { if (it == 1) Prefs.danmakuKeywords = DEFAULT_SHIELD else Prefs.danmakuKeywords = "" }
        ),
        SettingSpec(
            label = "不透明度",
            desc = "压低一点，别盖住画面细节",
            options = listOf("70%", "85%", "100%"),
            read = {
                when {
                    Prefs.danmakuAlpha <= 70 -> 0
                    Prefs.danmakuAlpha >= 100 -> 2
                    else -> 1
                }
            },
            write = {
                Prefs.danmakuAlpha = when (it) {
                    0 -> 70
                    2 -> 100
                    else -> 85
                }
            }
        ),
        SettingSpec(
            label = "显示区域",
            desc = "只铺上面一截，主播面部与比分不会被挡",
            options = listOf("1/3 屏", "上半屏", "全屏"),
            read = {
                when {
                    Prefs.danmakuAreaPercent <= 33 -> 0
                    Prefs.danmakuAreaPercent <= 50 -> 1
                    else -> 2
                }
            },
            write = {
                Prefs.danmakuAreaPercent = when (it) {
                    0 -> 33
                    1 -> 50
                    else -> 100
                }
            }
        )
    )

    2 -> listOf(
        SettingSpec(
            label = "登录",
            desc = "当前版本四个平台都不登录，清晰度以平台未登录返回为准",
            read = { 0 },
            write = {},
            disabled = true,
            note = "未登录"
        ),
        SettingSpec(
            label = "与手机同步",
            desc = "关注、观看记录、搜索历史通过同步码互通",
            read = { 0 },
            write = {},
            disabled = true,
            note = "v2"
        ),
        SettingSpec(
            label = "同步频率",
            desc = "越勤越准，流量也越多",
            options = listOf("每次打开", "每 10 分钟", "每天"),
            read = { 1 },
            write = {},
            disabled = true,
            note = "v2"
        ),
        SettingSpec(
            label = "跨设备续播",
            desc = "在手机上看到一半，电视接着放",
            read = { 0 },
            write = {},
            disabled = true,
            note = "v2"
        )
    )

    else -> listOf(
        SettingSpec(
            label = "版本",
            desc = "界面 v0.1.0 · 调试构建，版本号不对外",
            read = { 0 },
            write = {},
            disabled = true,
            note = "只读"
        ),
        SettingSpec(
            label = "内容来源",
            desc = "所有直播内容来自各平台公开接口，版权归原作者",
            read = { 0 },
            write = {},
            disabled = true,
            note = "只读"
        ),
        SettingSpec(
            label = "开源许可",
            desc = "本项目以 GPL-3.0 发布，仅供学习交流",
            read = { 0 },
            write = {},
            disabled = true,
            note = "只读"
        ),
        SettingSpec(
            label = "检查更新",
            desc = "本版没有内建更新通道，随应用商店更新",
            read = { 0 },
            write = {},
            disabled = true,
            note = "v2"
        ),
        SettingSpec(
            label = "清除本机缓存",
            desc = "只清封面缓存，不动关注与搜索历史",
            read = { 0 },
            write = {},
            disabled = true,
            note = "v2"
        )
    )
}
