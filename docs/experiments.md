# Experiment log

What was tried, what came out, and what it changed. Newest last. Numbers come from the
result folder named in each entry; `run.json` there says which script and commit made them.

Data is one user's own feed (Instagram Reels and YouTube Shorts on a Galaxy S24, Korean UI),
recorded by the app in Learning mode. Exports stay off git; see [results/](../results/README.md).

## 2026-10-24 · Preliminary statistics

Result: [results/2026-10-24-prelim](../results/2026-10-24-prelim/summary.md) · script: `ml/scripts/prelim_figures.py`

- 5 days (10-20 – 10-24), 1617 reels after `clean` (Instagram 1177, YouTube 440).
- 39–40% of non-ad reels are left within 2 s in both apps; median time on a reel is 3.1 s.
  First impressions decide most of it, so the SKIP label (< 2 s, then moved on) is common
  enough to learn from: SKIP 348, WATCH 577, 692 left unlabeled (2–5 s, ads, revisits, idle).
- Likes (6) and saves (1) are too rare to be the target. WATCH has to come from time on the
  reel, completion and opening the comments.
- 15% of reels are ads (Instagram 17%, YouTube 9%); Skip ads removed 219 of 237.
- Creator history alone (last day as test): skip AUC 0.54. Only 24% of the test day's creators
  had been seen before, so a content model is needed for the rest.

## 2026-10-25 · First skip model

Result: [results/2026-10-25-skip-model](../results/2026-10-25-skip-model/summary.md) · script: `ml/scripts/skip_model.py`

Question: from what the app knows about 0.3 s into a reel, can it tell a first-impression
SKIP from a WATCH?

Setup
- 810 labeled first impressions. Revisits are left out (scrolling back isn't a first
  impression), and so are reels without a first frame.
- Rolling folds: each of 10-22, 10-23 and 10-24 is a test day, trained on all earlier days.
  The headline is the mean of the per-day AUCs. Pooling the days into one AUC mixes their
  different skip shares (25–45%) and understated every model.
- Inputs were checked for availability before use, by how often each is missing per label:
  - Frames after the first exist for 97% / 94% of WATCH reels (0.8 s / 1.3 s) but only for
    69% / 29% of SKIP reels, so only the 0.3 s frame is used. The 540 px frame kept for OCR is
    the 1.3 s one, so OCR is out for now too.
  - Video length from the YouTube scrubber is known for 97% of WATCH reels and 76% of SKIP
    reels, so it is left out.
- Similarity scores: skip share among the 20 most similar earlier labeled reels, by first
  frame (MobileNetV3, the encoder the app ships, or CLIP ViT-B/32 as a stronger reference) or by
  caption + audio name (multilingual-e5-small). k = 20 was fixed beforehand.
- Model: logistic regression (median imputation, standardisation, C = 1).

Results
- Best: creator history + frame similarity + caption similarity ("Taste"): AUC 0.60
  (95% CI 0.55–0.65) with MobileNetV3, 0.605 with CLIP. The phone's encoder loses almost
  nothing to CLIP here.
- On their own: creator history 0.59, caption similarity 0.55, frame similarity 0.53.
- Context features (session position, previous reel's dwell, recent skip share, counts, time
  of day) flip direction from day to day. For example, the previous reel's dwell scores 0.58
  on one day and 0.36 the next. Viewing sessions swing between fast scrolling (median 1.8 s
  per reel) and watching (median 14–23 s). Adding context drags the model below chance on new
  days (0.49).
- Coverage at 80% precision is about 8% even with the best threshold per day. Auto mode
  cannot act on this.

Exploratory checks (looked at on the test days, so not used to pick the setup above)
- Deciding later, with that moment's frame (0.8 s: 69% of skips still catchable; 1.3 s:
  29%): frame similarity stays at 0.51–0.55. Screenshot timing is not the main limit.
- A sample of first frames shows some captured mid-swipe (half the previous reel) and others
  with app UI over part of the reel (e.g. Instagram's stories tray).
- A stricter WATCH (watched to the end, engaged, or ≥ 10 s) raises Taste to 0.62.
- k from 5 to 40 moves the similarity scores by up to 0.05 (caption similarity is highest at
  k = 40, 0.61).

What it changes
- The 0.70 AUC target is not met, and Auto mode stays off.
- Context features are dropped from the model for now; creator history stays.
- App: take the first frame once the feed has settled, crop it to the video's bounds, and keep
  the 540 px copy from that first frame, so OCR becomes usable at decision time.
- Next experiment: text on the frame (OCR → e5 similarity). The hook text burned into many
  reels is what gets read in the first second, and CLIP reads little Korean.
