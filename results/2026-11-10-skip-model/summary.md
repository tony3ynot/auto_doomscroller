# Skip model, first pass
- Data: 2026-10-20 – 2026-11-09, 3365 labeled first impressions (465 revisits / reels without a first frame left out)
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
| Creator history | 0.568 (0.54–0.60) | 0.539 (0.51–0.57) | 13% |
| Frame sim. (MobileNetV3) | 0.543 (0.52–0.57) | 0.533 (0.51–0.56) | 1% |
| Frame sim. (CLIP) | 0.554 (0.53–0.58) | 0.551 (0.53–0.58) | 2% |
| Caption sim. (e5) | 0.562 (0.53–0.59) | 0.566 (0.54–0.59) | 2% |
| Context | 0.588 (0.56–0.61) | 0.502 (0.48–0.53) | 5% |
| Taste (MobileNetV3) | 0.595 (0.57–0.62) | 0.607 (0.58–0.63) | 14% |
| Taste (CLIP) | 0.601 (0.57–0.63) | 0.614 (0.59–0.64) | 14% |
| Context + taste (MobileNetV3) | 0.635 (0.61–0.66) | 0.578 (0.55–0.60) | 16% |
| Context + taste (CLIP) | 0.635 (0.61–0.66) | 0.578 (0.55–0.60) | 17% |
