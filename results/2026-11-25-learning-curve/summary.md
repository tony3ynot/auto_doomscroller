# How many days of use the skip model needs
- Data: 2026-10-20 – 2026-11-24. Test period: the last 7 days
  (2026-11-18 – 2026-11-24), 1161 labeled first impressions
- Days of use: the model sees only that many days before the test period. It is fitted on their
  reels, and the history features start from nothing at the beginning of those days
- AUC: mean of the test days' AUCs, 95% bootstrap interval. The test reels are the same in every row
- Coverage: share of SKIPs caught at 80% precision by context + creator + frame head, with the best
  threshold for each day

| Days of use | Training reels | Context + creator + frame head | Context + creator | Coverage @80% |
|---|---|---|---|---|
| 1 | 93 | 0.576 (0.54–0.61) | 0.602 (0.56–0.64) | 6% |
| 2 | 196 | 0.644 (0.60–0.68) | 0.644 (0.61–0.68) | 15% |
| 3 | 302 | 0.638 (0.60–0.68) | 0.623 (0.58–0.66) | 18% |
| 4 | 555 | 0.669 (0.63–0.71) | 0.678 (0.64–0.72) | 25% |
| 5 | 647 | 0.678 (0.64–0.72) | 0.689 (0.65–0.73) | 28% |
| 6 | 771 | 0.695 (0.66–0.74) | 0.699 (0.66–0.74) | 28% |
| 7 | 859 | 0.685 (0.65–0.73) | 0.686 (0.65–0.73) | 27% |
| 9 | 1092 | 0.709 (0.67–0.75) | 0.710 (0.67–0.75) | 27% |
| 11 | 1249 | 0.709 (0.67–0.75) | 0.708 (0.67–0.75) | 27% |
| 13 | 1632 | 0.708 (0.67–0.75) | 0.717 (0.68–0.76) | 31% |
| 15 | 2040 | 0.711 (0.67–0.75) | 0.720 (0.68–0.76) | 29% |
| 17 | 2272 | 0.714 (0.67–0.75) | 0.719 (0.68–0.76) | 30% |
| 19 | 2665 | 0.719 (0.68–0.76) | 0.721 (0.68–0.76) | 32% |
| 21 | 3094 | 0.717 (0.68–0.76) | 0.718 (0.68–0.76) | 32% |
| 23 | 3391 | 0.719 (0.68–0.76) | 0.719 (0.68–0.76) | 33% |
| 25 | 3830 | 0.718 (0.68–0.76) | 0.718 (0.68–0.76) | 31% |
| 27 | 4046 | 0.719 (0.68–0.76) | 0.718 (0.68–0.76) | 32% |
| 28 | 4224 | 0.718 (0.68–0.76) | 0.721 (0.68–0.76) | 33% |
