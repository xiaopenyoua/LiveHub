#!/usr/bin/env python3
"""字标「LiveHub」→ 矢量路径（一次性提取，产物进 .v2-wordmark.json）

为什么不用 SVG <text>：
  SVG 母版要自包含。带 @font-face 的 SVG 依赖外部字体文件，Android 的
  VectorDrawable / 各种 SVG 渲染器根本不支持 <text>。所以把 Inter 600 的
  字形轮廓抽成 path，母版零依赖、零字体、跨渲染器逐字节一致。

坐标约定：
  输出 v 单位 = 字体 units（upm 2048），y 已翻成「向下为正」、基线 = 0。
  笔画在 y ∈ [-capHeight, +descender]。缩放交给 Node（× font-size / upm），
  本文件不出现任何 px。

轴的处理：
  InterVariable 有 wght / opsz 两个轴。只实例化 wght=600，opsz 保持默认值
  （=14，Text 切法）。**不要**让它跑 auto ——120px 以上 auto 会切到 Display，
  字宽缩 6.7%，那是 v1 已经踩过的坑（03 §4 的 3.847em 就是 Text 切法量出来的）。
"""
import json, sys
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen

ROOT = __file__.rsplit('/design/', 1)[0]
SRC = f'{ROOT}/assets/fonts/InterVariable.woff2'
OUT = f'{ROOT}/design/.v2-wordmark.json'
TEXT = 'LiveHub'
LETTER_SPACING_EM = -0.02          # 项目字标字距（01/03 一致）
WEIGHT = 600

f = TTFont(SRC)
upm = f['head'].unitsPerEm
axes = {a.axisTag: (a.minValue, a.defaultValue, a.maxValue) for a in f['fvar'].axes}
opsz_default = axes['opsz'][1] if 'opsz' in axes else None

inst = instantiateVariableFont(f, {'wght': WEIGHT}, inplace=False, updateFontNames=False)
gs = inst.getGlyphSet()
cmap = inst.getBestCmap()
hmtx = inst['hmtx']
os2 = inst['OS/2']
hhea = inst['hhea']

cap = os2.sCapHeight
asc, desc = hhea.ascent, hhea.descent
ls_u = round(LETTER_SPACING_EM * upm)          # -40.96 → -41? 不取整，保留精度

letters = []
pen_x = 0.0
for ch in TEXT:
    gname = cmap[ord(ch)]
    adv = hmtx[gname][0]
    spen = SVGPathPen(gs, ntos=lambda v: f'{v:.3f}')
    # 路径以「本位笔位 0」为原点输出（只翻 y），笔位由 Node 用带 kerning 的值去 translate。
    # 好处：同一段 d 在 banner / square / 开屏三处逐字相同，只有外层 scale 不同。
    tpen = TransformPen(spen, (1, 0, 0, -1, 0, 0))
    gs[gname].draw(tpen)
    bpen = BoundsPen(gs)
    gs[gname].draw(bpen)
    x0, y0, x1, y1 = bpen.bounds if bpen.bounds else (0, 0, 0, 0)
    letters.append({
        'ch': ch, 'glyph': gname, 'd': spen.getCommands(),
        'adv': adv, 'penPure': pen_x,
        'inkRel': [round(x0, 4), round(-y1, 4), round(x1, 4), round(-y0, 4)],
    })
    pen_x += adv + LETTER_SPACING_EM * upm

sum_adv = sum(l['adv'] for l in letters)
box_w = pen_x                                    # 含尾字距（CSS 盒宽口径）
live_adv = sum(l['adv'] for l in letters[:4])
hub_adv = sum(l['adv'] for l in letters[4:])
ink_x0 = min(l['penPure'] + l['inkRel'][0] for l in letters)
ink_x1 = max(l['penPure'] + l['inkRel'][2] for l in letters)
ink_w = ink_x1 - ink_x0
# Live / Hub 分界 = 'H' 的笔位（相对字标墨迹左缘；参考图 50.0% 就是这个口径）
boundary = letters[4]['penPure'] - ink_x0

out = {
    '_what': 'Inter wght=600 / opsz=默认 的字标轮廓，units 坐标系（upm 见下），y 向下、基线=0',
    '_gen': 'node → python3 design/.v2-wordmark.py',
    'source': 'assets/fonts/InterVariable.woff2',
    'text': TEXT, 'weight': WEIGHT,
    'upm': upm, 'opszDefault': opsz_default, 'opszAxes': axes.get('opsz'),
    'capHeight': cap, 'ascender': asc, 'descender': desc,
    'letterSpacingUnits': LETTER_SPACING_EM * upm,
    'letters': letters,
    'sumAdvance': sum_adv,
    'liveAdvance': live_adv, 'hubAdvance': hub_adv,
    'boxWidth': round(box_w, 4),
    'inkWidth': round(ink_w, 4),
    'inkX0': round(ink_x0, 4),
    'inkX1': round(ink_x1, 4),
    'boundaryFromInkLeft': round(boundary, 4),
    'boundaryPct': round(100 * boundary / ink_w, 4),
    'rawBoundaryPct': round(100 * live_adv / sum_adv, 4),
}

with open(OUT, 'w') as fh:
    json.dump(out, fh, ensure_ascii=False, indent=1)

# ---- 断言：与 08-品牌裁决-v2 §3 的表逐格对齐 ----
fail = []
def chk(name, got, want, tol=0.01):
    if abs(got - want) > tol:
        fail.append(f'{name}: 算得 {got} ≠ 08 §3 的 {want}')
chk('Live 进宽', live_adv, 4107)
chk('Hub 进宽', hub_adv, 4059)
chk('LiveHub 进宽', sum_adv, 8166)
chk('字距', round(LETTER_SPACING_EM * upm, 2), -40.96)
chk('盒宽 em', box_w / upm, 3.8473, 5e-4)

print(f'upm {upm} | cap {cap} ({cap/upm:.5f}em) | asc {asc} desc {desc}')
print(f'opsz 默认 {opsz_default}（轴 {axes.get("opsz")}）')
print(f'Live {live_adv} / Hub {hub_adv} / 合计 {sum_adv} | 字距 {LETTER_SPACING_EM*upm:.2f}u')
print(f'盒宽 {box_w:.3f}u = {box_w/upm:.5f}em | 墨宽 {ink_w:.3f}u = {ink_w/upm:.5f}em | 墨左 {ink_x0:.3f}u')
print(f'分界 裸 {100*live_adv/sum_adv:.4f}% | 带字距 {100*boundary/ink_w:.4f}%（08 §3 说 50.045%）')
print('逐字墨盒（u）: ' + ' '.join(f'{l["ch"]}[{l["penPure"]+l["inkRel"][0]:.1f},{l["penPure"]+l["inkRel"][2]:.1f}]' for l in letters))
if fail:
    print('\n断言失败：'); [print('  ' + x) for x in fail]; sys.exit(1)
print('\n全部断言通过 → ' + OUT)
