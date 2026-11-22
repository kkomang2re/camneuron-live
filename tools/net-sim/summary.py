"""net-sim이 쓴 CSV들을 한 줄씩 요약한다: python3 summary.py out/*.csv

peak  서버에 닿은 영상이 가장 늦었던 시간 (초). 시청자 지연은 이 값만큼 늘어난 뒤 잘 돌아오지 않는다.
>3s   영상이 3초 넘게 늦게 닿은 초 수
avg   평균 목표 비트레이트 (kbps). 낮을수록 화질이 깨져 보인다.
full  원래 비트레이트로 보낸 초 수
"""
import csv
import os
import sys

print(f"{'file':24} {'peak':>5} {'>3s':>4} {'avg':>6} {'full':>9} {'frames':>6} {'dropped':>7}")
for path in sys.argv[1:]:
    with open(path) as f:
        data = [{k: int(v) for k, v in row.items()} for row in csv.DictReader(f)]
    lag = [r["video_lag_max_ms"] for r in data]
    target = [r["target_kbps"] for r in data]
    print(f"{os.path.basename(path):24} {max(lag) / 1000:5.1f} {sum(1 for v in lag if v > 3000):4} "
          f"{sum(target) / len(target):6.0f} {sum(1 for v in target if v >= max(target)):4}/{len(data):<4} "
          f"{sum(r['video_frames'] for r in data):6} {data[-1]['dropped_total']:7}")
