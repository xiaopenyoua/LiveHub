#!/usr/bin/env bash
#
# 接口哨兵 —— 四家直播平台的关键接口逐个打一遍。
#
# 为什么要有这个东西：
#   这四家都不是公开 API，任何一家改字段、加签名、改域名，App 表现都是
#   「首页少了几张卡」或者「点了播不出来」，看不出是谁的问题。
#   上线前 / 改完取流层之后跑一次，能立刻定位到是哪一家的哪一个接口变了。
#
# 每个接口断言两件事：HTTP 200 + 响应里出现某个「只有成功才会有的字段」。
#   只看 200 是不够的 —— 哔哩风控时会返回 HTTP 200 + {"code":-352}，
#   抖音 reflow 参数错了也是 200 + {"data":{"message":"Request params error"}}。
#
# 用法：
#   ./tools/probe-sites.sh            # 全跑
#   ./tools/probe-sites.sh huya       # 只跑虎牙（bili/douyu/huya/douyin）
#
# 退出码：0 全部通过；1 有硬失败
#
set -uo pipefail

CURL="${CURL:-curl}"
UA_PC="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
UA_MOBILE="Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

# 固定房间：选长期在播的大房间，避免「主播下播」被误判成接口挂了
BILI_ROOM=6          # 短号 6 → 真实 room_id 7734200（S13 赛事直播间）
DOUYU_RID=288016     # 斗鱼官方赛事
HUYA_RID=660000      # 虎牙 LOL 赛事
# 抖音这里放的是 web_rid（直播间地址里 6–12 位的号），不是 19 位 room_id。
# 房间下播不影响本项判定：reflow 对「存在但已下播」的房间照样返回 status_code 0 + room 对象，
# 而「在播 / 已下播」只有 room.status（2/4）能区分 —— 两者 stream_url 都是满配的（见 docs/06 §4.6）。
DOUYIN_WEBRID="${DOUYIN_WEBRID:-517422039354}"
# 备用房间：只在主房间配不上号时才用，用来分辨「这个房间没了」和「页面结构变了」。
# 抖音是本产品唯一没有兜底的平台，一声红必须能直接告诉人该换房间号还是该改解析。
DOUYIN_WEBRID_ALT="${DOUYIN_WEBRID_ALT:-715855351148}"

ONLY="${1:-all}"

PASS=0
FAIL=0
SKIP=0
TMP="$(mktemp -t livehub-probe)"
trap 'rm -f "$TMP"' EXIT

md5hex() {
  if command -v md5 >/dev/null 2>&1; then printf '%s' "$1" | md5 -q
  else printf '%s' "$1" | md5sum | cut -d' ' -f1; fi
}
now_ms() { echo $(( $(date +%s) * 1000 )); }

# check <平台> <接口名> <期望片段(ERE)> <url> [curl 参数...]
# 判定只有三种：ok（HTTP 200 + 命中断言）、跳过、FAIL。**没有「软失败」这一态**——
# 「失败了但放过」是含糊通过，它让退出码失去意义，最后没人看结果。
# 环境变量 SKIP_OK=1 时失败只计「跳过」、不影响退出码。当前**没有任何一项在用**：
# 抖音已是硬断言（见下方抖音段），这个开关只为将来临时排查某个已知会抖的接口时先用着。
# 什么时候该用「跳过」：HTTP 成功 + 结构正确 + 只是房间当前不在直播（打印原因，不计通过也不计失败）。
# 什么时候**不许**用：断言没命中（结构变了）、非 200、响应是错误对象 —— 这些一律 FAIL。
check() {
  local platform="$1" name="$2" expect="$3" url="$4"
  shift 4

  local code payload hit t0 t1 ms
  t0="$(now_ms)"
  code="$("$CURL" -sS -m 12 -o "$TMP" -w '%{http_code}' -A "$UA_PC" "$@" "$url" 2>/dev/null)"
  t1="$(now_ms)"
  ms=$(( t1 - t0 ))
  payload="$(cat "$TMP" 2>/dev/null)"

  if [ "$code" = "200" ] && printf '%s' "$payload" | grep -qE "$expect"; then
    hit="ok"
    PASS=$((PASS + 1))
  elif [ "${ALLOW_OFFLINE_SKIP:-0}" = "1" ] &&
       [ "$code" = "200" ] &&
       printf '%s' "$payload" | grep -qE '"error":104|"msg":"房间未开播'; then
    # 「取流」类接口的唯一合法跳过：HTTP 成功 + 结构正确（是 JSON、error 字段在）+ 只是
    # 房间当前不在直播。**不许**因为「断言没命中但看着像临时问题」就走到这里——
    # 判定条件是响应里明确说「未开播」，不是我们的猜测。
    hit="跳过"
    SKIP=$((SKIP + 1))
  elif [ "${SKIP_OK:-0}" = "1" ]; then
    hit="跳过"
    SKIP=$((SKIP + 1))
  else
    hit="FAIL"
    FAIL=$((FAIL + 1))
  fi

  # 逐字节截断，避免多字节字符被切半导致 tr 报 illegal byte sequence
  printf '  %-6s %-24s %-4s %-4s %5sms  %s\n' \
    "$platform" "$name" "$code" "$hit" "$ms" \
    "$(printf '%s' "$payload" | LC_ALL=C cut -c1-88 | tr -d '\n\r' | tr -s ' ')"
}

hr() { printf -- '------------------------------------------------------------------------------------------------\n'; }

printf '\nLiveHub 接口哨兵  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')"
hr
printf '  %-6s %-24s %-4s %-4s %7s  %s\n' "平台" "接口" "HTTP" "判定" "耗时" "响应片段"
hr

# ---------------------------------------------------------------- 哔哩哔哩
if [ "$ONLY" = "all" ] || [ "$ONLY" = "bili" ] || [ "$ONLY" = "bilibili" ]; then
  # 短号→真实房间号。不做这一步，getRoomPlayInfo 拿不到流
  check bili "room_init" '"room_id"' \
    "https://api.live.bilibili.com/room/v1/Room/room_init?id=${BILI_ROOM}" \
    -H "Referer: https://live.bilibili.com/"

  check bili "getRoomPlayInfo" '"playurl_info"' \
    "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id=7734200&protocol=0,1&format=0,1,2&codec=0,1&qn=10000&platform=web&ptype=8&dolby=5&panorama=1" \
    -H "Referer: https://live.bilibili.com/"

  # 首页信息流。注意：second/getList 与 getListByArea 都已上 wbi 风控（-352），
  # 只有这个老接口还免签，见 docs/06
  check bili "get_user_recommend(首页)" '"roomid"' \
    "https://api.live.bilibili.com/room/v1/room/get_user_recommend?page=1&page_size=30" \
    -H "Referer: https://live.bilibili.com/"

  # 弹幕 token + 服务器列表。注意用 getConf：getDanmuInfo 已上 wbi，未登录直接 -352
  check bili "Danmu/getConf(弹幕)" '"token"|"host_server_list"' \
    "https://api.live.bilibili.com/room/v1/Danmu/getConf?room_id=7734200&platform=pc&player=web" \
    -H "Referer: https://live.bilibili.com/"
fi

# ------------------------------------------------------------------- 斗鱼
if [ "$ONLY" = "all" ] || [ "$ONLY" = "douyu" ]; then
  TS="$(now_ms)"
  AUTH="$(md5hex "${DOUYU_RID}${TS}")"

  # hlsH5Preview：免签名，只要 md5(rid+毫秒)。斗鱼最稳的一条
  # 房间下播时这里返回 {"error":104,"msg":"房间未开播"}（HTTP 仍是 200）——
  # 那是合法状态，按 §7 的规矩计「跳过」。不然哨兵会每天红一次，红久了就没人看了。
  ALLOW_OFFLINE_SKIP=1
  check douyu "hlsH5Preview" '"rtmp_url"|"rtmp_live"' \
    "https://playweb.douyucdn.cn/lapi/live/hlsH5Preview/${DOUYU_RID}" \
    -H "rid: ${DOUYU_RID}" -H "time: ${TS}" -H "auth: ${AUTH}" \
    -H "Referer: https://www.douyu.com/" \
    --data "rid=${DOUYU_RID}&did=10000000000000000000000000001501"
  ALLOW_OFFLINE_SKIP=0

  # 首页 = 全站列表；分类页 = 同一个接口换 cid1_cid2
  check douyu "directory/0_0(首页)" '"rid"' \
    "https://www.douyu.com/gapi/rkc/directory/0_0/1" \
    -H "Referer: https://www.douyu.com/"

  check douyu "directory/2_1(分类)" '"rid"' \
    "https://www.douyu.com/gapi/rkc/directory/2_1/1" \
    -H "Referer: https://www.douyu.com/"
fi

# ------------------------------------------------------------------- 虎牙
if [ "$ONLY" = "all" ] || [ "$ONLY" = "huya" ]; then
  # 首页信息流，120 条/页，字段见 docs/06
  check huya "getLiveListByPage(首页)" '"profileRoom"' \
    "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&tagAll=0&page=1" \
    -H "Referer: https://www.huya.com/"

  # showSecret=1 时带 sFlvAntiCode —— anti-code 服务端已算好，不用自己算 wsSecret
  check huya "profileRoom(取流)" '"sFlvAntiCode"|"sHlsUrl"' \
    "https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=${HUYA_RID}&showSecret=1" \
    -H "Referer: https://www.huya.com/"

  # 分类：tagAll=1 + tagName，tagName 需 urlencode，这里用「英雄联盟」
  check huya "getLiveListByPage(分类)" '"profileRoom"' \
    "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&tagAll=1&tagName=%E8%8B%B1%E9%9B%84%E8%81%94%E7%9B%9F&page=1" \
    -H "Referer: https://www.huya.com/"
fi

# ------------------------------------------------------------------- 抖音
# pair_room_id <web_rid> → 打印 "相邻配对:<19位>" / "反序配对:<19位>" / ":"（没配上）
#
# 只认**相邻**的两种顺序：
#   "roomId":"19位","web_rid":"输入"
#   "web_rid":"输入","roomId":"19位"
# 不认「往前找最近的 roomId」。理由有两层：
#   1) 一个直播间页实测有 78 个 19 位数字，其中 77 个是推荐位/作品 id，「最近的那个」
#      没有任何保证是这一个房间的 —— 配错就是放别人家的直播，而且不报错，比失败更糟。
#   2) 早先那版用 `${html%%"\"web_rid\":\"$web\"*"}` 取前文，在「页面里根本没有这个
#      web_rid」（房间注销、短链失效落到首页）时会返回整页，于是「最近的那个」退化成
#      「整页最后一个 roomId」—— 房间不存在也能配出一个号。同一个写法还会让 bash 在
#      900KB 的页面上卡死（实测 >12s 无返回），所以这条路整条删掉，不留。
pair_room_id() {
  local web="$1" html id
  html="$("$CURL" -sS -m 15 -A "$UA_PC" -H "Referer: https://live.douyin.com/" \
    "https://live.douyin.com/${web}" 2>/dev/null | sed 's/\\"/"/g')"
  id="$(printf '%s' "$html" | grep -oE "\"roomId\":\"[0-9]{10,25}\",\"web_rid\":\"${web}\"" \
    | head -1 | grep -oE '[0-9]{10,25}' | head -1)"
  if [ -n "$id" ]; then printf '相邻配对:%s' "$id"; return; fi
  id="$(printf '%s' "$html" | grep -oE "\"web_rid\":\"${web}\",\"roomId\":\"[0-9]{10,25}\"" \
    | head -1 | grep -oE '[0-9]{10,25}' | tail -1)"
  if [ -n "$id" ]; then printf '反序配对:%s' "$id"; return; fi
  printf ':'
}

if [ "$ONLY" = "all" ] || [ "$ONLY" = "douyin" ]; then
  # 抖音走两步，两步都是硬断言：
  #   ① live.douyin.com/{web_rid} 页面里做 "roomId" / "web_rid" 的【严格配对】→ 19 位 room_id
  #   ② reflow/info 只认 19 位 room_id，断言 status_code:0 且响应里真的有 "room":
  #
  # 为什么不能只做第 ② 步：直接拿 web_rid 去调 reflow，会得到
  #   HTTP 200 + {"data":{"message":"Request params error"}}（status_code 4001038）。
  # 这就是「HTTP 200 不构成成功证明」的活例子 —— 断言只写 "data" 会把它误判成成功。
  # 为什么断言 "room": 而不是 "stream_url"：下播房间（status 4）的 stream_url 一样满配
  #   4 路 FLV + 4 路 HLS，只是拉起来 404/超时（见 docs/06 §4.6）。拿 stream_url 当判据
  #   区分不出「接口挂了」和「房间下播」，只剩一个会看心情变红的假闹钟。
  #
  # 为什么失败路径要多打一次页面：抖音是四家里唯一没有兜底的平台，一声红必须能直接
  #   告诉人「该换房间号」还是「该改解析」。主房间配不上就拿备用房间再配一次——
  #   两个都配不上 = 页面结构变了；只有主房间配不上 = 那个房间没了。猜错的代价太大。

  t0="$(now_ms)"
  res="$(pair_room_id "$DOUYIN_WEBRID")"
  how="${res%%:*}"
  DROOM="${res##*:}"
  retryNote=""
  if [ -z "$DROOM" ]; then
    # 只在失败路径多一次请求：用备用房间分辨「房间没了」与「解析坏了」
    alt="$(pair_room_id "$DOUYIN_WEBRID_ALT")"
    altRoom="${alt##*:}"
    if [ -n "$altRoom" ]; then
      DROOM="$altRoom"
      how="兜底房间"
      retryNote="${DOUYIN_WEBRID} 已不在页面上（换一个 DOUYIN_WEBRID）；本次用备用 ${DOUYIN_WEBRID_ALT} 验下一步"
    else
      retryNote="两个房间（${DOUYIN_WEBRID} / ${DOUYIN_WEBRID_ALT}）都配不上：抖音页面结构变了，免签换算通道可能已关"
    fi
  fi
  t1="$(now_ms)"

  if [ -n "$DROOM" ] && [ -z "$retryNote" ]; then
    PASS=$((PASS + 1))
    printf '  %-6s %-24s %-4s %-4s %5sms  %s\n' \
      "douyin" "页面配对→room_id" "200" "ok" "$((t1 - t0))" "$how ${DROOM}"
  else
    FAIL=$((FAIL + 1))
    printf '  %-6s %-24s %-4s %-4s %5sms  %s\n' \
      "douyin" "页面配对→room_id" "200" "FAIL" "$((t1 - t0))" "$retryNote"
  fi

  if [ -n "$DROOM" ]; then
    # status_code:0 与 "room": 必须同时出现（顺序不定，JSON 是单行，用 ERE 两边都写）
    check douyin "reflow/info(免签取流)" '"status_code":0.*"room":|"room":.*"status_code":0' \
      "https://webcast.amemv.com/webcast/room/reflow/info/?type_id=0&live_id=1&room_id=${DROOM}&app_id=1128" \
      -H "Referer: https://live.douyin.com/"
  else
    FAIL=$((FAIL + 1))
    printf '  %-6s %-24s %-4s %-4s %5sms  %s\n' \
      "douyin" "reflow/info(免签取流)" "-" "FAIL" "0" "未执行：上一步两次配对都失败，无从判断 reflow 是否还活着"
  fi
fi

hr
printf '  通过 %d   失败 %d   跳过 %d（跳过只有一种合法原因：斗鱼取流那项的房间当前未开播，响应里明确写着 error=104）\n\n' "$PASS" "$FAIL" "$SKIP"

if [ "$FAIL" -gt 0 ]; then
  printf '  有接口硬失败。按响应片段对号入座：\n'
  printf '    "code":-352 / -412 → 哔哩风控。换免签接口，别加签名逻辑（见 android/docs/接口实测.md）\n'
  printf '    404                 → 域名或路径改了，去抓一次网页版确认\n'
  printf '    200 但空 data       → 参数不全（did/UA/Referer 缺失最常见）\n'
  printf '    200 但 Request params error → 抖音 room_id 位数不对，必须是 19 位（见 android/docs/接口实测.md §4）\n'
  printf '    429                 → 限频。等一分钟再跑，别循环重试\n\n'
  exit 1
fi

printf '  开工前检查通过。\n\n'
exit 0
