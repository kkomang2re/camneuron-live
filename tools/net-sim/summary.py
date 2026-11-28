"""net-sim이 쓴 CSV들을 한 줄씩 요약한다: python3 summary.py out/*.csv

view  끝났을 때의 시청자 지연 (초, 모형). 망이 좋을 때는 5초다. 따라잡지 않는 플레이어를 가정한다 (Sim.kt의 Viewer).
      낮게 묶으려는 값은 이것이다.
worst 망이 좋은 동안(20 Mbps) 가장 컸던 시청자 지연 (초, 모형)
stall 시청자 화면이 멈춘 횟수 (모형: 받아 둔 것이 떨어진 횟수)
skip  폰이 건너뛰어 방송에서 빠진 시간 (초)

peak  서버에 닿은 영상이 가장 늦었던 시간 (초)
audio 서버에 닿은 소리가 가장 늦었던 시간 (초)
end   마지막 5초 동안 가장 늦었던 시간 (초, 영상·소리 중 큰 쪽). 망이 좋아진 뒤에 돌아왔는지.
>3s   영상이나 소리가 3초 넘게 늦게 닿은 초 수
avg   평균 목표 비트레이트 (kbps). 낮을수록 화질이 깨져 보인다.
full  원래 비트레이트로 보낸 초 수
"""
import csv
import os
import sys

print(f"{'file':24} {'view':>5} {'worst':>5} {'stall':>5} {'skip':>5} {'peak':>5} {'audio':>5} {'end':>5} {'>3s':>4} {'avg':>6} {'full':>9} {'frames':>6} {'dropped':>7}")
for path in sys.argv[1:]:
    with open(path) as f:
        data = [{k: int(v) for k, v in row.items()} for row in csv.DictReader(f)]
    lag = [r["video_lag_max_ms"] for r in data]
    target = [r["target_kbps"] for r in data]
    audio = [r["audio_lag_max_ms"] for r in data]
    end = max(lag[-5:] + audio[-5:])
    view = [r.get("viewer_delay_ms", -1) for r in data]
    good = [v for v, r in zip(view, data) if r["link_kbps"] >= 20000]
    print(f"{os.path.basename(path):24} {view[-1] / 1000:5.1f} {max(good) / 1000:5.1f} {data[-1].get('viewer_stalls', -1):5} {data[-1].get('skipped_total_ms', 0) / 1000:5.1f} {max(lag) / 1000:5.1f} {max(audio) / 1000:5.1f} {end / 1000:5.1f} "
          f"{sum(1 for a, b in zip(lag, audio) if max(a, b) > 3000):4} "
          f"{sum(target) / len(target):6.0f} {sum(1 for v in target if v >= max(target)):4}/{len(data):<4} "
          f"{sum(r['video_frames'] for r in data):6} {data[-1]['dropped_total']:7}")
