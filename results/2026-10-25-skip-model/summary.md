# Skip model, first pass
- Data: 2026-10-20 – 2026-10-24, 810 labeled first impressions (115 revisits / reels without a first frame left out)
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
| Creator history | 0.590 (0.54–0.64) | 0.518 (0.46–0.57) | 12% |
| Frame sim. (MobileNetV3) | 0.534 (0.48–0.58) | 0.503 (0.44–0.57) | 3% |
| Frame sim. (CLIP) | 0.536 (0.48–0.59) | 0.564 (0.51–0.62) | 0% |
| Caption sim. (e5) | 0.551 (0.50–0.60) | 0.526 (0.47–0.58) | 1% |
| Context | 0.515 (0.47–0.56) | 0.467 (0.41–0.53) | 0% |
| Taste (MobileNetV3) | 0.600 (0.55–0.65) | 0.578 (0.51–0.63) | 8% |
| Taste (CLIP) | 0.605 (0.56–0.65) | 0.581 (0.52–0.64) | 8% |
| Context + taste (MobileNetV3) | 0.494 (0.45–0.54) | 0.465 (0.41–0.52) | 2% |
| Context + taste (CLIP) | 0.487 (0.44–0.54) | 0.452 (0.40–0.51) | 4% |
