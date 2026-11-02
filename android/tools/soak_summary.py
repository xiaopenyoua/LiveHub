#!/usr/bin/env python3
"""汇总一次浸泡（`soak.py` 的产物）。

用法：
  python3 android/tools/soak_summary.py /tmp/livehub-soak/bili10b

只读产物，不连设备。输出：
  - 采样点 / 存活率 / pid 变化
  - 首帧 latencyMs
  - stats 序列：线号、帧数、丢帧、重缓冲、音频欠载
  - stalled / STATE_ENDED / player error / FATAL / ANR 计数
"""
import csv
import os
import re
import sys

STATS = re.compile(
    r"stats pos=(?P<pos>\d+)s line=(?P<line>\d+)/(?P<lines>\d+) quality=(?P<quality>.*?) "
    r"firstFrame=(?P<first>true|false) playing=(?P<playing>true|false) "
    r"buffering=(?P<buffering>true|false) stalled=(?P<stalled>true|false) "
    r"ended=(?P<ended>true|false) video=(?P<vw>\d+)x(?P<vh>\d+) frames=(?P<frames>\d+) "
    r"(?:decoder=(?P<decoder>.*?) )?bitrateKbps=(?P<bitrate>\d+) dropped=(?P<dropped>\d+) "
    r"rebuffer=(?P<rc>\d+)/(?P<rms>\d+)ms audioUnderruns=(?P<au>\d+) error=(?P<error>.*)"
)
FIRST = re.compile(r"first frame rendered .*latencyMs=(?P<latency>\d+)")


def main():
    d = sys.argv[1]
    rows = []
    sample_path = os.path.join(d, "samples.csv")
    if os.path.exists(sample_path):
        with open(sample_path, newline="", encoding="utf-8") as f:
            rows = list(csv.DictReader(f))

    alive = [r for r in rows if r.get("alive") == "1"]
    pids = sorted({r.get("pid", "") for r in rows if r.get("pid")})
    print(f"samples={len(rows)} alive={len(alive)} pids={pids}")

    log_path = os.path.join(d, "player.log")
    text = open(log_path, encoding="utf-8", errors="replace").read() if os.path.exists(log_path) else ""
    # soak.py 每分钟把「当前 logcat 全量」追加一次，同一行会出现很多次；
    # 去重后才数事件，否则 STATE_ENDED/first frame 会被重复放大。
    text = "\n".join(dict.fromkeys(text.splitlines()))
    firsts = [int(m.group("latency")) for m in FIRST.finditer(text)]
    if firsts:
        print(f"first_frame_latency_ms: first={firsts[0]} count={len(firsts)} max={max(firsts)}")

    stats = list(STATS.finditer(text))
    if stats:
        lines = sorted({int(m.group("line")) for m in stats})
        qualities = sorted({m.group("quality") for m in stats})
        max_frames = max(int(m.group("frames")) for m in stats)
        max_dropped = max(int(m.group("dropped")) for m in stats)
        max_rc = max(int(m.group("rc")) for m in stats)
        max_rms = max(int(m.group("rms")) for m in stats)
        max_au = max(int(m.group("au")) for m in stats)
        stalled = sum(1 for m in stats if m.group("stalled") == "true")
        ended = sum(1 for m in stats if m.group("ended") == "true")
        errors = sorted({m.group("error") for m in stats if m.group("error") != "-"})
        print(f"stats={len(stats)} lines_seen={lines} qualities={qualities}")
        print(
            f"max_frames={max_frames} max_dropped={max_dropped} "
            f"max_rebuffer={max_rc}/{max_rms}ms max_audio_underrun={max_au}"
        )
        print(f"stalled_samples={stalled} ended_samples={ended} errors={errors}")
    else:
        print("no stats lines")

    for key, pat in [
        ("STATE_ENDED", r"STATE_ENDED"),
        ("player_error", r"player error"),
        ("load_error", r"load error"),
        ("stalled_log", r"stalled:"),
        ("FATAL", r"FATAL EXCEPTION"),
        ("ANR", r"ANR in"),
    ]:
        print(f"{key}={len(re.findall(pat, text))}")


if __name__ == "__main__":
    main()
