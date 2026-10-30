#!/usr/bin/env bash
# 取流层验收（可跑版）：把 docs/04 §3.3.1 第二节/第五节的验收从「人工照做」变成红/绿。
#
# 两条硬规矩：
#   1. 退出码来自被测命令本身（不接 `| tail`，管道会把 gradle 的失败吞掉）；
#   2. 开头打印被测对象，避免「跑绿了但跑的不是我以为的那套」。
#
# 用法：./android/tools/run-unit-tests.sh
set -u
cd "$(dirname "$0")/.." || exit 1   # -> android/

echo "被测对象："
echo "  · Net 的 HTTP → SiteError 表（13 个码）与 IO 异常归类（TLS 靠 detail 前缀区分）"
echo "  · DouyinSite 入参形状（19 位直连 reflow / 4–12 位 web_rid 走页面 / 其余 0 次请求）"
echo "  · HuyaSite「非 2xx 重试一次」（数 Net 调用次数，只能这么验）"
echo "  · app/src/test 源集本身（能否编译、能否看见 main）"
echo

./gradlew :app:testDebugUnitTest --console=plain
status=$?

echo
# 变量一律写成 ${status}：`$status）`（全角括号紧跟）会被 bash 当成变量名的一部分，
# 而这一行只在**失败分支**执行 —— 也就是「最需要它的时候」才炸，绿的时候永远发现不了。
if [ "${status}" -eq 0 ]; then
  echo "结果：全部单测通过（exit=0）"
else
  echo "结果：单测未通过（exit=${status}），报告在 app/build/reports/tests/testDebugUnitTest/index.html"
fi
exit "${status}"
