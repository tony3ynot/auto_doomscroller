# Predictions made on the phone
- 1627 reels scored on the phone, 2026-11-19 – 2026-11-24, by a model fitted on
  3365 reels up to 2026-11-09: every one of these days is new to it

Same answer as offline?
- 1627 of them have a stored first frame, so the offline code can score them from the same weights
- The two probabilities differ by 0.003 at the median and 0.022 at the 95th percentile;
  99% are within 0.05, and they correlate at 0.998

Right about the user?
- 1001 of the scored reels got a label (38% SKIP)
- Skip AUC of the phone's probabilities, mean of the days: 0.704
  (0.66–0.75); of the offline ones on the same reels: 0.704
  (0.66–0.75)
- By day: 0.77, 0.74, 0.70, 0.71, 0.75, 0.54. Inside a viewing session: 0.637
- Coverage at 80% precision with the best threshold for each day: 33%

| P(skip) at least | Reels Auto mode would skip | Of those, really skipped | Share of all SKIPs |
|---|---|---|---|
| 0.5 | 168 | 80% | 35% |
| 0.6 | 136 | 85% | 30% |
| 0.7 | 115 | 89% | 27% |
| 0.8 | 86 | 92% | 21% |

Ready in time?
- The probability was ready 150 ms into the reel at the median and
  264 ms at the 95th percentile; 98% within half a second
- Of that, 32 ms (95th percentile 78 ms) came after the
  first frame was captured: preparing it, the encoder and the model
