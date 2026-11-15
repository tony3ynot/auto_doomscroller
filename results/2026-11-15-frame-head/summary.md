# Frame signal: similarity or a learned head
- Data: 2026-10-20 – 2026-11-09, 3365 labeled first impressions
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
| Context + creator | 0.632 (0.61–0.66) | 0.573 (0.55–0.60) | 14% | – |
| + frame sim. | 0.630 (0.61–0.66) | 0.572 (0.54–0.60) | 14% | −0.001 (−0.005 to +0.003) |
| + caption sim. | 0.637 (0.61–0.66) | 0.579 (0.55–0.60) | 17% | +0.006 (−0.003 to +0.014) |
| + frame sim. + caption sim. | 0.635 (0.61–0.66) | 0.578 (0.55–0.60) | 16% | +0.004 (−0.005 to +0.011) |
| + frame head, 4 PCs | 0.640 (0.61–0.66) | 0.573 (0.55–0.60) | 14% | +0.008 (+0.000 to +0.016) |
| + frame head, 8 PCs | 0.645 (0.62–0.67) | 0.582 (0.56–0.61) | 13% | +0.013 (+0.002 to +0.023) |
| + frame head, 16 PCs | 0.641 (0.61–0.67) | 0.585 (0.56–0.61) | 12% | +0.009 (−0.002 to +0.021) |
| + frame head, 32 PCs | 0.636 (0.61–0.66) | 0.586 (0.56–0.61) | 13% | +0.005 (−0.011 to +0.020) |
| + frame head (CLIP), 8 PCs | 0.649 (0.62–0.67) | 0.601 (0.58–0.63) | 14% | +0.018 (+0.007 to +0.028) |
| + caption head, 8 PCs | 0.642 (0.62–0.67) | 0.599 (0.57–0.62) | 14% | +0.011 (−0.002 to +0.023) |
