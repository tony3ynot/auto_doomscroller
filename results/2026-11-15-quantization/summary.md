# Encoder quantization, off the phone
- Data: 2026-10-20 – 2026-11-09, 3365 labeled first impressions, each with
  its first frame embedded by every variant (MobileNetV3-Small, 576-d, LiteRT on a PC)
- Cosine to FP32: between a frame's embedding and its FP32 embedding; mean, 5th percentile in brackets
- Same neighbours: of a frame's 20 nearest frames under FP32, the share still among its 20
  nearest under the variant
- Skip model: context + creator + 8 principal components of the frame embedding, refitted on
  each variant's embeddings. Folds: test on each day from the third on, train on all earlier days
- P(skip) shift: mean absolute change of the predicted skip probability against the FP32 model
- AUC: mean of the per-day AUCs, 95% bootstrap interval. AUC change: against FP32 on the same
  reels, paired 95% bootstrap interval. Coverage: share of SKIPs caught at 80% precision, with the
  best threshold for each day
- No encoder: the same model without the frame, for scale

| Encoder | Size | Cosine to FP32 | Same neighbours | P(skip) shift | Skip AUC | AUC change | Coverage @80% |
|---|---|---|---|---|---|---|---|
| FP32 | 3.74 MB | – | – | – | 0.645 (0.62–0.67) | – | 13% |
| FP16 | 1.93 MB | 1.000 (1.000) | 99% | 0.000 | 0.645 (0.62–0.67) | +0.000 (+0.000 to +0.001) | 13% |
| INT8 dynamic range | 1.12 MB | 0.980 (0.964) | 71% | 0.013 | 0.641 (0.62–0.66) | −0.004 (−0.008 to +0.001) | 13% |
| INT8 full integer | 1.23 MB | 0.793 (0.642) | 29% | 0.032 | 0.642 (0.61–0.67) | −0.003 (−0.014 to +0.006) | 13% |
| No encoder | – | – | – | – | 0.632 (0.61–0.66) | −0.013 (−0.023 to −0.002) | 14% |
