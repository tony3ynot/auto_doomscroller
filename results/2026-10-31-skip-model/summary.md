# Skip model, first pass
- Data: 2026-10-20 – 2026-10-25, 933 labeled first impressions (129 revisits / reels without a first frame left out)
- Folds: test on each day from the third on, train on all earlier days
- Similarity (sim.): skip share among the 20 most similar earlier labeled reels, by first frame
  (0.3 s) or by caption + audio name. Taste = creator history + frame sim. + caption sim.
- Context: app, media type, like / comment counts, caption length, audio, paid promotion, time of
  day, position and minutes into the viewing session, previous reel's dwell, recent skip share
- AUC: mean of the per-day AUCs. Within-session AUC: ranking inside each viewing session
  (5 min gap = new session), weighted by size. Brackets: 95% bootstrap intervals
- Coverage: share of SKIPs caught at 80% precision, with the best threshold for each day
  (an upper bound; Auto mode has to pick its threshold in advance)

| Feature set | AUC | Within-session AUC | Coverage @80% |
|---|---|---|---|
| Majority | 0.500 | – | – |
| Creator history | 0.629 (0.58–0.68) | 0.530 (0.47–0.58) | 12% |
| Frame sim. (MobileNetV3) | 0.563 (0.52–0.61) | 0.520 (0.46–0.58) | 2% |
| Frame sim. (CLIP) | 0.546 (0.50–0.60) | 0.553 (0.51–0.61) | 0% |
| Caption sim. (e5) | 0.556 (0.51–0.60) | 0.521 (0.47–0.57) | 2% |
| Context | 0.556 (0.51–0.60) | 0.472 (0.42–0.53) | 1% |
| Taste (MobileNetV3) | 0.622 (0.57–0.67) | 0.584 (0.53–0.63) | 10% |
| Taste (CLIP) | 0.622 (0.58–0.67) | 0.593 (0.54–0.64) | 8% |
| Context + taste (MobileNetV3) | 0.563 (0.52–0.61) | 0.490 (0.44–0.55) | 5% |
| Context + taste (CLIP) | 0.558 (0.51–0.60) | 0.475 (0.42–0.53) | 11% |
