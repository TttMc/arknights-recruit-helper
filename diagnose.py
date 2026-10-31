"""
诊断标签按钮检测：对公招界面截图跑一遍与 App 相同的检测流程，
输出每个连通域的尺寸/长宽比/填充率，以及被哪条规则拒绝。

只用 Pillow + numpy（不依赖 opencv）。

用法：
    python diagnose.py <截图1> [截图2 ...]
"""
import sys
from collections import deque

import numpy as np
from PIL import Image


def detect(gray, sw, mw, mh, minW, maxW, minH, maxH):
    """与 Java 端一致的检测：二值化 -> 4 邻域连通域 -> 规则过滤"""
    dark = gray < 95
    label = np.zeros((mh, mw), dtype=np.int32)
    cur = 0
    passed, rejected = [], []

    ys, xs = np.nonzero(dark)
    coords = list(zip(ys.tolist(), xs.tolist()))

    for (sy, sx) in coords:
        if not dark[sy, sx] or label[sy, sx] != 0:
            continue
        cur += 1
        stack = [(sy, sx)]
        label[sy, sx] = cur
        minx, miny, maxx, maxy, cnt = mw, mh, 0, 0, 0
        while stack:
            y, x = stack.pop()
            cnt += 1
            if x < minx: minx = x
            if y < miny: miny = y
            if x > maxx: maxx = x
            if y > maxy: maxy = y
            for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                if 0 <= ny < mh and 0 <= nx < mw and dark[ny, nx] and label[ny, nx] == 0:
                    label[ny, nx] = cur
                    stack.append((ny, nx))

        bw, bh = maxx - minx + 1, maxy - miny + 1
        area = cnt
        aspect = bw / float(bh) if bh else 0
        fill = area / float(bw * bh) if bw * bh else 0

        rec = dict(x=minx * sw, y=miny * sw, w=bw * sw, h=bh * sw,
                   sw=bw, sh=bh, area=area,
                   aspect=round(aspect, 2), fill=round(fill, 3))
        reasons = []
        if bw < minW: reasons.append("宽%d<%d" % (bw, minW))
        if bw > maxW: reasons.append("宽%d>%d" % (bw, maxW))
        if bh < minH: reasons.append("高%d<%d" % (bh, minH))
        if bh > maxH: reasons.append("高%d>%d" % (bh, maxH))
        if aspect < 1.4: reasons.append("比%.2f<1.4" % aspect)
        if aspect > 7.0: reasons.append("比%.2f>7.0" % aspect)
        if fill < 0.50: reasons.append("填%.2f<0.5" % fill)
        rec["reasons"] = reasons
        (rejected if reasons else passed).append(rec)

    return passed, rejected


def analyze(path):
    print("=" * 70)
    print("图片:", path)
    im = Image.open(path).convert("RGB")
    W, H = im.size
    print("尺寸: %dx%d" % (W, H))

    arr = np.asarray(im)
    a = arr.astype(np.int32)
    gray_full = (a[:, :, 0] * 299 + a[:, :, 1] * 587 + a[:, :, 2] * 114) // 1000

    shortPx = min(W, H)
    sw = max(1, shortPx // 320)
    mw, mh = max(1, W // sw), max(1, H // sw)
    gray = gray_full[::sw, ::sw][:mh, :mw].astype(np.int32)
    print("采样: sw=%d -> %dx%d" % (sw, mw, mh))

    short = min(mw, mh)
    minW = max(6, short // 10)
    maxW = int(short * 0.85)
    minH = max(4, short // 45)
    maxH = max(8, short // 4)
    print("阈值: W[%d..%d]  H[%d..%d]  填充率>=0.50  长宽比[1.4..7.0]"
          % (minW, maxW, minH, maxH))
    print("暗像素占比: %.1f%%" % (100.0 * (gray < 95).mean()))

    passed, rejected = detect(gray, sw, mw, mh, minW, maxW, minH, maxH)

    print("\n--- 通过 %d 个 ---" % len(passed))
    for r in sorted(passed, key=lambda r: (r["y"] // 40, r["x"])):
        print("  (%5d,%5d) %4dx%-4d 采样%3dx%-3d 比%-5s 填%-5s"
              % (r["x"], r["y"], r["w"], r["h"], r["sw"], r["sh"],
                 r["aspect"], r["fill"]))

    cand = [r for r in rejected if r["w"] >= 20 and r["h"] >= 10]
    print("\n--- 被拒的疑似按钮 %d 个（宽>=20 高>=10）---" % len(cand))
    for r in sorted(cand, key=lambda r: (r["y"] // 40, r["x"]))[:30]:
        print("  (%5d,%5d) %4dx%-4d 采样%3dx%-3d 比%-5s 填%-5s  ← %s"
              % (r["x"], r["y"], r["w"], r["h"], r["sw"], r["sh"],
                 r["aspect"], r["fill"], ", ".join(r["reasons"])))

    # ===== 精确模拟 pickTagGroup =====
    print("\n--- pickTagGroup 结果 ---")
    if passed:
        rows = []
        for r in sorted(passed, key=lambda r: (r["y"], r["x"])):
            placed = False
            for row in rows:
                tol = max(8, min(row[0]["h"], r["h"]) // 2)
                if abs(r["y"] - row[0]["y"]) <= tol:
                    row.append(r); placed = True; break
            if not placed:
                rows.append([r])

        def evenly(row):
            if len(row) < 2:
                return False
            s = sorted(row, key=lambda r: r["x"])
            if len(s) == 2:
                gap = s[1]["x"] - (s[0]["x"] + s[0]["w"])
                return 0 <= gap < s[0]["w"] * 2
            gaps = [s[k]["x"] - s[k-1]["x"] for k in range(1, len(s))]
            avg = sum(gaps) / len(gaps)
            if avg <= 0:
                return False
            var = sum((g - avg) ** 2 for g in gaps) / len(gaps)
            return (var ** 0.5) / avg < 0.12

        def drop_outliers(row):
            if len(row) <= 2:
                return row
            s = sorted(row, key=lambda r: r["x"])
            gaps = [s[i]["x"] - s[i-1]["x"] for i in range(1, len(s))]
            if not gaps:
                return s
            sg = sorted(gaps)
            median = sg[(len(sg) - 1) // 2]
            if median <= 0:
                return s
            best_start, best_len = 0, 1
            for i in range(len(s)):
                ln = 1
                for j in range(i + 1, len(s)):
                    gap = s[j]["x"] - s[j-1]["x"]
                    if abs(gap - median) <= median * 0.35:
                        ln += 1
                    else:
                        break
                if ln > best_len:
                    best_start, best_len = i, ln
            return s[best_start:best_start + best_len]

        good = []
        for row in rows:
            if len(row) < 2:
                continue
            clean = drop_outliers(row)
            if len(clean) < 2:
                print("    剔除（清理后不足2个）y=%d" % row[0]["y"])
                continue
            if not evenly(clean):
                print("    剔除（不等距）y=%d x=%s" % (clean[0]["y"], [r["x"] for r in clean]))
                continue
            if len(clean) != len(row):
                print("    清理离群: y=%d %d个→%d个 x=%s"
                      % (row[0]["y"], len(row), len(clean), [r["x"] for r in clean]))
            good.append(clean)

        print("    等距行 %d 个:" % len(good))
        for k, row in enumerate(good):
            print("      行%d y=%d n=%d x=%s"
                  % (k, row[0]["y"], len(row), [r["x"] for r in row]))

        best_score, best_group = -1, []
        for a in range(len(good)):
            group = list(good[a])
            prev_top, prev_left = good[a][0]["y"], good[a][0]["x"]
            for b in range(a + 1, len(good)):
                row = good[b]
                if abs(row[0]["x"] - prev_left) > 8:
                    break
                gap = row[0]["y"] - prev_top
                if gap <= 0 or gap > max(60, row[0]["h"] * 4):
                    break
                h1, h2 = group[0]["h"], row[0]["h"]
                hmin, hmax = min(h1, h2), max(h1, h2)
                if hmin <= 0 or (hmax - hmin) / float(hmin) > 0.15:
                    print("    断（高度不一致 %d vs %d）y=%d" % (h1, h2, row[0]["y"]))
                    break
                group.extend(row)
                prev_top, prev_left = row[0]["y"], row[0]["x"]
            score = len(group) * 100 + len(set(round(r["y"] / 16) for r in group))
            if score > best_score:
                best_score, best_group = score, group

        print("  ==> 选中 %d 个:" % len(best_group))
        for r in sorted(best_group, key=lambda r: (r["y"], r["x"])):
            print("      y=%-5d x=%-5d %dx%d" % (r["y"], r["x"], r["w"], r["h"]))
    print("\n--- 通过者的 y 值分布（看是否只覆盖一行）---")
    if passed:
        ys = sorted(set(r["y"] for r in passed))
        print("  y 值:", ys[:20])
        rows = sorted(set(r["y"] // (short // 3) for r in passed))
        print("  行分组数:", len(rows))
    return passed, rejected


def main():
    if len(sys.argv) < 2:
        print("用法: python diagnose.py <截图...>")
        return 1
    for p in sys.argv[1:]:
        analyze(p)
    return 0


if __name__ == "__main__":
    sys.exit(main())
