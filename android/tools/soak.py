#!/usr/bin/env python3
"""LiveHub 直播浸泡采样器。

用途：在模拟器/真机上连续播放一个房间，定时截图 + 拉取播放器统计，用来判断
「30 分钟后还在不在播」。**只读设备，不修改应用数据。**

用法（在仓库根目录或任意目录）：
  ADB=/Users/wangq/Library/Android/sdk/platform-tools/adb \
  python3 android/tools/soak.py --tag bili30 --seconds 1800 --shot-every 60 \
      --out /tmp/livehub-soak

汇总：
  python3 android/tools/soak_summary.py /tmp/livehub-soak/bili30

设计约束（都是踩过的坑）：
1. 不用 `uiautomator dump`：本环境会逐次退化并吞按键。
2. 不用 `dumpsys meminfo`：它会强制目标进程 full GC，内存读数与 GC 次数都是伪影。
3. 不用 `dumpsys SurfaceFlinger --latency`：本 AVD 恒返回一行刷新率，判不了出帧。
4. 截图用 `adb exec-out screencap -p`，stdout 重定向到**文件**再由 Python 读取，
   避免 adbd 持有管道导致子进程永不返回。
5. 统计只看应用自己打的 `LiveHubPlayer: stats ...`（30s 一条），不额外打扰进程。

退出码：0 = 跑完；2 = 设备不在线。
"""
import argparse
import csv
import os
import subprocess
import sys
import time

ADB = os.environ.get("ADB", "adb")
SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5554")
PKG = "com.livehub.tv"


def cap(args, timeout=40):
    """跑 adb，stdout 落临时文件再读，避免 daemon 持管道。"""
    import tempfile
    fd, path = tempfile.mkstemp(prefix="soak_adb_", dir="/tmp")
    os.close(fd)
    try:
        with open(path, "wb") as out:
            p = subprocess.Popen(
                [ADB, "-s", SERIAL] + args,
                stdout=out, stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL,
            )
            try:
                rc = p.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                p.kill()
                return 124, b""
        with open(path, "rb") as f:
            return rc, f.read()
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


def shot(path):
    for _ in range(3):
        rc, data = cap(["exec-out", "screencap", "-p"], timeout=50)
        if rc == 0 and len(data) > 20000:
            with open(path, "wb") as f:
                f.write(data)
            return True
        time.sleep(1.5)
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True)
    ap.add_argument("--seconds", type=int, default=1800)
    ap.add_argument("--shot-every", type=int, default=60)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    out = os.path.join(args.out, args.tag)
    shots = os.path.join(out, "shots")
    os.makedirs(shots, exist_ok=True)

    rc, pid = cap(["shell", "pidof", PKG])
    if not pid.strip():
        print(f"设备上没有运行 {PKG}；先启动应用再跑浸泡", file=sys.stderr)
        return 2

    meta = open(os.path.join(out, "meta.txt"), "w", encoding="utf-8")
    samples = open(os.path.join(out, "samples.csv"), "w", newline="", encoding="utf-8")
    w = csv.writer(samples)
    w.writerow(["elapsed_s", "pid", "alive", "shot"])
    plog = open(os.path.join(out, "player.log"), "w", encoding="utf-8")

    start = time.time()
    meta.write(
        f"tag={args.tag} start={int(start)} duration_s={args.seconds} "
        f"shot_every={args.shot_every} serial={SERIAL}\n"
    )
    meta.flush()

    n = 0
    while time.time() - start < args.seconds:
        elapsed = int(time.time() - start)
        path = os.path.join(shots, f"{n:04d}.png")
        ok = shot(path)
        _, pidb = cap(["shell", "pidof", PKG])
        pid = pidb.decode("utf-8", "replace").strip()
        w.writerow([elapsed, pid, 1 if pid else 0, path if ok else ""])
        samples.flush()

        _, lb = cap([
            "shell", "logcat", "-d", "-s",
            "LiveHubPlayer:I", "AndroidRuntime:E", "ActivityManager:E", "LiveHubFocus:W",
        ])
        plog.write(f"===== t={elapsed} pid={pid} =====\n")
        plog.write(lb.decode("utf-8", "replace"))
        plog.flush()

        n += 1
        if not ok:
            meta.write(f"shot_failed at {elapsed}s\n")
            meta.flush()
        time.sleep(max(1, args.shot_every))

    meta.write(f"end={int(time.time())} samples={n}\n")
    meta.close()
    samples.close()
    plog.close()
    print(f"done tag={args.tag} samples={n} out={out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
