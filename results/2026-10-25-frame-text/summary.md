# Text on the frame
- Data: 2026-10-20 – 2026-10-25; folds test on each day from the third on
- Frame text: OCR (EasyOCR, Korean + English) with the app's own UI, counts, the author row and
  the creator's name removed, embedded with multilingual-e5-small. Similarity: skip share among
  the 20 most similar earlier labeled reels. Taste = creator history + frame sim. + caption sim.
- AUC: mean of the per-day AUCs. Within-session AUC: ranking inside each viewing session
  (5 min gap = new session), weighted by size. Brackets: 95% bootstrap intervals
- Coverage: share of SKIPs caught at 80% precision, with the best threshold for each day
  (an upper bound; Auto mode has to pick its threshold in advance)

## A. First frame (0.3 s, 224 px), 933 first impressions
Text found on 76% of them.

| Feature set | AUC | Within-session AUC | Coverage @80% |
|---|---|---|---|
| Taste (MobileNetV3) | 0.622 (0.57–0.67) | 0.584 (0.53–0.63) | 10% |
| Frame text sim. (224 px) | 0.576 (0.53–0.62) | 0.543 (0.49–0.60) | 1% |
| Taste + frame text (224 px) | 0.613 (0.57–0.66) | 0.583 (0.53–0.64) | 8% |

## B. Still on screen at 1.3 s, with a 540 px frame: 642 reels (17% SKIP)
Text found on 84% of them (540 px).

| Feature set | AUC | Within-session AUC | Coverage @80% |
|---|---|---|---|
| Taste (MobileNetV3) | 0.674 (0.59–0.74) | 0.639 (0.53–0.71) | 4% |
| Frame text sim. (224 px) | 0.464 (0.36–0.57) | 0.385 (0.31–0.49) | 0% |
| Frame text sim. (540 px) | 0.629 (0.53–0.72) | 0.644 (0.55–0.72) | 9% |
| Taste + frame text (224 px) | 0.675 (0.58–0.76) | 0.665 (0.56–0.75) | 6% |
| Taste + frame text (540 px) | 0.655 (0.56–0.74) | 0.671 (0.57–0.74) | 4% |
