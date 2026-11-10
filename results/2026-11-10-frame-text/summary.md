# Text on the frame
- Data: 2026-10-25 – 2026-11-09, 2438 labeled first impressions with a
  540 px first frame (30% SKIP); text found on 83% of them
- Folds: test on each day from the third on, train on all earlier days
- Frame text: OCR (EasyOCR, Korean + English) of the first frame with the app's own UI, counts, the
  author row and the creator's name removed, embedded with multilingual-e5-small. Similarity: skip
  share among the 20 most similar earlier labeled reels. Taste = creator history + frame sim. +
  caption sim. Context: as in the skip model (app, counts, time of day, place in the session, ...)
- AUC: mean of the per-day AUCs. Within-session AUC: ranking inside each viewing session
  (5 min gap = new session), weighted by size. Brackets: 95% bootstrap intervals
- Coverage: share of SKIPs caught at 80% precision, with the best threshold for each day
  (an upper bound; Auto mode has to pick its threshold in advance)

| Feature set | AUC | Within-session AUC | Coverage @80% |
|---|---|---|---|
| Taste (MobileNetV3) | 0.597 (0.56–0.63) | 0.626 (0.59–0.66) | 14% |
| Frame text sim. | 0.552 (0.52–0.58) | 0.558 (0.53–0.59) | 1% |
| Taste + frame text | 0.599 (0.56–0.63) | 0.624 (0.59–0.66) | 14% |
| Context + taste | 0.651 (0.62–0.68) | 0.612 (0.58–0.64) | 15% |
| Context + taste + frame text | 0.653 (0.62–0.68) | 0.612 (0.58–0.64) | 15% |
