# Frame signal: similarity or a learned head
- Data: 2026-10-20 – 2026-11-24, 5385 labeled first impressions
- Folds: test on each day from the third on, train on all earlier days
- Context + creator: the skip model's context features, the creator's skip share so far and how
  often the creator has been seen. Every other row adds to it
- sim.: skip share among the 20 most similar earlier labeled reels, by first frame (MobileNetV3)
  or caption + audio name (multilingual-e5-small)
- head: the embedding's leading principal components (PCs), fitted on the training days, as
  extra inputs to the logistic regression. Frame: MobileNetV3-Small unless CLIP is named
- AUC: mean of the per-day AUCs. Within-session AUC: ranking inside each viewing session
  (5 min gap = new session), weighted by size. Brackets: 95% bootstrap intervals
- Coverage: share of SKIPs caught at 80% precision, with the best threshold for each day
  (an upper bound; Auto mode has to pick its threshold in advance)
- AUC change: against Context + creator on the same reels, with a paired 95% bootstrap interval

| Feature set | AUC | Within-session AUC | Coverage @80% | AUC change |
|---|---|---|---|---|
| Context + creator | 0.644 (0.62–0.66) | 0.588 (0.57–0.61) | 17% | – |
| + frame sim. | 0.645 (0.62–0.66) | 0.588 (0.57–0.61) | 18% | +0.000 (−0.002 to +0.003) |
| + caption sim. | 0.647 (0.63–0.67) | 0.594 (0.58–0.62) | 19% | +0.003 (−0.003 to +0.008) |
| + frame sim. + caption sim. | 0.647 (0.63–0.67) | 0.595 (0.58–0.62) | 18% | +0.002 (−0.004 to +0.008) |
| + frame head, 4 PCs | 0.654 (0.63–0.67) | 0.597 (0.58–0.62) | 17% | +0.010 (+0.004 to +0.016) |
| + frame head, 8 PCs | 0.657 (0.64–0.68) | 0.601 (0.58–0.62) | 17% | +0.013 (+0.005 to +0.021) |
| + frame head, 16 PCs | 0.657 (0.64–0.68) | 0.604 (0.58–0.62) | 16% | +0.012 (+0.003 to +0.021) |
| + frame head, 32 PCs | 0.654 (0.63–0.67) | 0.605 (0.59–0.63) | 17% | +0.010 (+0.000 to +0.021) |
| + frame head (CLIP), 8 PCs | 0.654 (0.63–0.67) | 0.609 (0.59–0.63) | 17% | +0.009 (+0.001 to +0.017) |
| + caption head, 8 PCs | 0.650 (0.63–0.67) | 0.603 (0.59–0.63) | 17% | +0.005 (−0.004 to +0.013) |
