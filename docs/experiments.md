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

## 2026-10-25 · Frame capture fixed in the app

Commits `a71e38f`, `fc24e4c`. Follows from the first skip model's look at the first frames.

- A reel becomes current while the swipe that brings it in is still finishing. The service now
  waits until the page fills the feed (on the test phone that happens 0–7 ms after the reel is
  detected) and takes the first frame right then, 60–140 ms into the reel instead of 300 ms.
  Every frame is cropped to the reel's area, and a frame is dropped if the reel moved while it
  was taken.
- At the top of the Instagram home the stories tray pushes the reel down rather than covering
  it, so cropping to the reel leaves the tray out.
- The 540 px copy now comes from the first frame, so OCR can run when a decision is due.
  Exports record `first_frame_ms` (schema v9).
- Checked on 7 new reels (4 Instagram, 3 YouTube): all cropped correctly, none mid-swipe. One
  first frame was black: the first reel after opening Instagram, captured before its video had
  drawn. A flat first frame is now retaken a moment later.
- Rows recorded before this have full-screen frames and must not be mixed with new rows in one
  embedding experiment ([data format](data-format.md#thumbnails)).

## 2026-10-25 · Text on the frame

Result: [results/2026-10-25-frame-text](../results/2026-10-25-frame-text/summary.md) · script: `ml/scripts/frame_text.py`

Question: does the text burned into a reel (hook text, subtitles) predict a first-impression
skip, on top of Taste?

Setup
- Newer export: 10-20 – 10-25 (6 days), 933 labeled first impressions; folds test on 10-22
  through 10-25.
- OCR: EasyOCR (Korean + English), standing in for ML Kit on the phone. App UI is removed by
  position (top tab bar, right-hand buttons, author and caption block at the bottom) and by a
  short list of UI words, bare counts and the creator's name. OCR misspells the tab labels, so
  position does most of the work.
- Frame text similarity: skip share among the 20 most similar earlier labeled reels by the e5
  embedding of that text.
- A: the 224 px first frame, upscaled 2×, on all first impressions (text found on 76%).
- B: reels still on screen at 1.3 s, which have a 540 px frame (642 reels, 17% SKIP), to see
  how much OCR quality matters.

Results
- Taste on this data: AUC 0.62 (95% CI 0.57–0.67).
- A: frame text alone scores 0.58, the best single content signal on the first frame so far
  (the frame image scored 0.53 and the caption 0.55 in the first pass). Added to Taste it
  doesn't help: 0.61.
- B: OCR quality matters. On the same reels, text from the 540 px frame scores 0.63 alone,
  text from the 224 px frame 0.46. Added to Taste, the 540 px text gives a within-session AUC
  of 0.67 against 0.64 and a per-day AUC of 0.66 against 0.67. B has only about 110 skips, so
  the intervals are far too wide to tell.
- OCR offline costs about 2 s per frame on one CPU thread. On the phone it would be ML Kit,
  still to be measured.

What it changes
- Text from the 224 px frame is not worth adding.
- The real test needs a sharp first frame, which the app records since `a71e38f`. Re-run B's
  comparison on all first impressions once a few hundred labeled reels have been recorded that
  way.
- Taste (0.62) stays the model to beat.

## 2026-10-31 · Six days, for the proposal

Results: [results/2026-10-31-prelim](../results/2026-10-31-prelim/summary.md),
[results/2026-10-31-skip-model](../results/2026-10-31-skip-model/summary.md) · same scripts,
newer export (10-20 – 10-25, 1833 reels, 933 first impressions)

- 38% of non-ad reels are left within 2 s in both apps; half of the SKIPs happen within 1.1 s.
- Creator history alone now scores 0.63 (95% CI 0.58–0.68), slightly above Taste (0.62): with
  one more day, content similarity adds nothing on top of it yet.
- MobileNetV3 and CLIP frame similarity: 0.56 and 0.55. Context still hurts (0.56).
- These are the numbers in the proposal ([paper/proposal](../paper/proposal/main.tex)).

## 2026-11-10 · Hands-free gestures on the phone

Commit `e47539f`. Tested on the Galaxy S24 with the debug logs, on one face (the author's).

- Cost: the face landmarker takes 33–67 ms and the gesture recognizer 37–95 ms per frame on the
  CPU, so the camera is analysed about 8 times a second instead of the 10 asked for.
- Winks are hard to tell from blinks on this face: a wink squints the other eye too. The
  difference between the two eyes' blink scores was 0.11–0.19 during winks and 0.04–0.21 during
  natural blinks. The eye aspect ratio from the face mesh overlaps the same way. Only
  exaggerated winks (0.23–0.30) cleared the 0.25 margin, and a lower margin lets blinks through.
- So "next" is now mainly a long blink: both eyes shut for 0.8–2.5 s, counted when they open.
  In the last run 3 of 3 long blinks and 4 exaggerated double winks moved on, and about 25 s
  of normal watching gave no command.
- A held thumbs-up scored 0.65–0.77; the threshold went from 0.6 to 0.5. A real like didn't
  register as an accessibility click on the button and works as a tap on it.
- Still owed: a longer false-positive run (minutes of normal watching) and the battery cost,
  with the other benchmarks.

## 2026-11-10 · Three weeks

Results: [results/2026-11-10-prelim](../results/2026-11-10-prelim/summary.md),
[results/2026-11-10-skip-model](../results/2026-11-10-skip-model/summary.md),
[results/2026-11-10-frame-text](../results/2026-11-10-frame-text/summary.md) · same scripts,
export 10-20 – 11-09 (21 days, 6755 reels, 3365 labeled first impressions)

- 11-10 is left out (`--until 2026-11-09`, Seoul time): that night's reels were skipped to test
  the hands-free gestures, not by taste.
- 32% of non-ad reels are left within 2 s in both apps (38% at six days). Still almost no
  explicit engagement: 7 likes and 1 save.

Skip model (six-day numbers from the proposal in brackets)
- Creator history: 0.57 (95% CI 0.54–0.60) [0.63]. On the last day only 45% of creators had
  been seen before, and the creator baseline there is at chance (0.49). The six-day lead didn't
  last.
- Taste (MobileNetV3): 0.60 (0.57–0.62) [0.62]. It ranks reels within a session best (0.61).
- Context alone: 0.59 [0.56]. Context + taste is now the best overall, 0.64 (0.61–0.66) [0.56],
  catching 16% of SKIPs at 80% precision. Within a session it ranks worse than Taste (0.58 against
  0.61): context tells a session of quick skips from a patient one (time of day, recent skip
  share, time into the session), and taste ranks the reels inside it.
- CLIP is no better than MobileNetV3: 0.55 against 0.54 as frame similarity alone, 0.60 for
  Taste either way.
- The kNN memory mixes full-screen frames (before 10-25) with cropped ones. That costs nothing:
  on the reels since 10-25, keeping only cropped frames in memory moves every set by 0.015 or
  less (a one-off check, not kept).

Text on the sharp first frame
- 2438 labeled first impressions since 10-25 have a 540 px first frame (30% SKIP); OCR finds
  text on 83% of them.
- Frame text alone scores 0.55 (0.52–0.58), level with frame (0.54) and caption (0.56)
  similarity. The 0.63 on 10-25 came from reels still on screen at 1.3 s, a subset with few
  skips.
- Added to Taste: 0.597 → 0.599. Added to Context + taste: 0.651 → 0.653. Within-session AUC
  doesn't move.

What it changes
- Context + taste with MobileNetV3 is the model to beat and the one to put on the phone; the
  skip-model figure now highlights it.
- OCR stays off the phone. The frame text carries nothing that the creator, frame and caption
  don't already, so model v2 needs only the frame and caption encoders.
- Coverage is the weak point: at 80% precision the model catches about one SKIP in six, and
  that is with each day's threshold picked after the fact. Auto mode will have to act on few
  reels or accept more wrong skips.

## 2026-11-15 · A learned head for the frame

Result: [results/2026-11-15-frame-head](../results/2026-11-15-frame-head/summary.md) ·
script: `ml/scripts/frame_head.py` · the same three weeks, 3365 labeled first impressions

Context + taste was taken apart to see what each encoder buys, since each one is a model the
phone has to run. Changes are against context + creator on the same reels, with a paired
bootstrap; that is much tighter than setting two intervals side by side.

- Context + creator alone: 0.632 (0.61–0.66). The frame similarity score adds −0.001 (−0.005
  to +0.003) and the caption similarity score +0.006 (−0.003 to +0.014). Nearly all of
  Context + taste's 0.635 came from the part that needs no encoder.
- The same frame embedding as a learned head, 8 principal components into the logistic
  regression: +0.013 (+0.002 to +0.023), 0.645. Within-session AUC 0.58 against 0.57.
- 4, 16 and 32 components give +0.008, +0.009 and +0.005, with intervals that reach zero. Eight
  was the best of four tries on this data, so read +0.013 as the generous end.
- Putting all 576 dimensions in, shrunk, does worse than leaving the frame out (0.60–0.63; a
  one-off check, not kept).
- CLIP as a head: +0.018 (+0.007 to +0.028), and it ranks within a session better (0.60). A
  caption head: +0.011 (−0.002 to +0.023), within-session 0.60.
- Coverage at 80% precision stays at 12–14% for every head; the base has 14%.

What it changes
- The model for the phone is context + creator + 8 principal components of the MobileNetV3
  embedding. Projection, scaling and regression fold into one weight vector, and there is no
  memory of past embeddings to keep and search. This replaces Context + taste from the
  three-weeks entry.
- No caption encoder on the phone. multilingual-e5-small has about 118 M parameters, over a
  hundred times the frame encoder's, for a gain the data can't tell from zero.
- CLIP stays a reference: 0.005 over MobileNetV3 doesn't pay for a ViT-B/32 on every reel.
- What a reel shows is a small part of the decision. The frame adds about 0.01 to the 0.63 the
  session's state and the creator already give.

## 2026-11-15 · Encoder quantization, off the phone

Result: [results/2026-11-15-quantization](../results/2026-11-15-quantization/summary.md) ·
scripts: `autodoom.quantize`, `ml/scripts/quantization.py` · the same 3365 first frames

The MobileNetV3-Small encoder in four LiteRT builds. The skip model is the one above, refitted
on each build's own embeddings. Latency and energy need the phone and come next.

| Encoder | Size | Cosine to FP32 (5th pct) | Same 20 neighbours | P(skip) shift | Skip AUC | Change vs FP32 |
|---|---|---|---|---|---|---|
| FP32 | 3.74 MB | – | – | – | 0.645 | – |
| FP16 | 1.93 MB | 1.000 (1.000) | 99% | 0.000 | 0.645 | +0.000 (+0.000 to +0.001) |
| INT8 dynamic range | 1.12 MB | 0.980 (0.964) | 71% | 0.013 | 0.641 | −0.004 (−0.008 to +0.001) |
| INT8 full integer | 1.23 MB | 0.793 (0.642) | 29% | 0.032 | 0.642 | −0.003 (−0.014 to +0.006) |
| No encoder | – | – | – | – | 0.632 | −0.013 (−0.023 to −0.002) |

- Skip AUC can't rank the builds. Dropping the encoder altogether costs 0.013, which leaves
  little for rounding its weights to take away, and every build's change has an interval that
  includes zero. The embeddings themselves show the cost.
- FP16 is free: half the size, and the embeddings agree to three decimals.
- INT8 dynamic range (8-bit weights, float activations) keeps the direction of each embedding
  but reshuffles close neighbours: 71% of a frame's 20 nearest stay.
- INT8 full integer moves the embeddings a long way, cosine 0.79, and keeps 29% of the
  neighbours. Calibration isn't the cause: the earlier calibration set gives 0.82 and 1000
  frames instead of 200 give 0.75, against 0.79 (on 500 frames outside every calibration set;
  a one-off check). 16-bit activations aren't an option, the converter has no 16-bit HARD_SWISH.
- The skip model still holds with full integer because it is refitted on those embeddings and
  uses only their 8 broadest directions. Anything that leans on fine distances, like the
  nearest-neighbour scores, would feel it.
- LiteRT FP32 reproduces the Keras model exactly (cosine 1.000000 on every frame), so the
  cached embeddings of the earlier entries are the FP32 build's.
- Full-integer calibration now takes 200 first frames spread evenly over the collection. It
  used to take the first 200 files in name order, later frames of the reel included.

What it changes
- FP16 is the default to ship unless the phone shows an integer build is clearly faster.
- Embeddings from different builds don't mix. The head has to be fitted on embeddings from
  the build that runs, which it is when training happens on the phone.
- The comparison to report is size, latency and energy against embedding fidelity, with skip
  AUC as the check that the task doesn't suffer.

## 2026-11-18 · Encoder latency on the phone

Result: [results/2026-11-18-encoder-latency](../results/2026-11-18-encoder-latency/summary.md) ·
scripts: `scripts/benchmark-encoder.sh`, `ml/scripts/encoder_latency.py` · Galaxy S24
(SM-S921N, Exynos 2400), Android 17, LiteRT 1.4.2

The same four files as in the entry above (the checksums match), timed by an instrumented
test: one 224 px frame in, one embedding out. Each build runs 200 times back to back, then
40 times with a second of idle before each run, which is how the app will use it, once per
reel. Median, 95th percentile in brackets; CPU, one thread.

| Encoder | Size | Back to back | Paced |
|---|---|---|---|
| FP32 | 3.74 MB | 3.6 ms (3.6) | 21.8 ms (25.3) |
| FP16 | 1.93 MB | 3.6 ms (3.7) | 22.5 ms (25.2) |
| INT8 dynamic range | 1.12 MB | 2.9 ms (2.9) | 23.7 ms (26.7) |
| INT8 full integer | 1.23 MB | 2.3 ms (2.3) | 18.0 ms (21.3) |

- Back to back, full integer is the fastest: 2.3 ms against 3.6 ms, with dynamic range at
  2.9 ms. FP16 runs exactly as fast as FP32.
- Paced, the same inference takes 18–24 ms, about six times longer. The benchmark doesn't
  show why; presumably the cores have slowed down after a second with nothing to do. The
  fastest paced run of a build is 3–10 ms, so some runs catch the phone still awake.
- Paced, the three other builds are within 2 ms of each other and full integer is about 4 ms
  ahead. A first run with 20 paced samples put all four at 14–17 ms in another order (a
  one-off, not kept), so the paced figure moves by several milliseconds between runs.
- Threads make no difference: 1, 2 and 4 give the same times both ways.
- GPU: building the interpreter takes 1.3–1.8 s against 27–77 ms on the CPU. Back to back it
  is no faster (3.7–3.9 ms); paced it takes 16–18 ms.
- The phone reported no thermal throttling during the run. It was on USB power.

What it changes
- Latency doesn't pick the build. The decision has about 1.5 s, and the encoder takes about
  20 ms of it whichever build runs. Full integer's 4 ms doesn't pay for embeddings at cosine
  0.79. FP16 ships: half the size of FP32, the same embeddings, the same speed.
- CPU, one thread. The GPU would save about 5 ms per reel for more than a second at start-up
  and another native library in the app.
- Encoder timings are to be quoted paced. The back-to-back 3.6 ms understates the cost of a
  reel six times over.
- Not in these numbers: scaling the screenshot to 224 px and the head. The time from a reel
  appearing to a decision is measured in the app, once it runs the model.

## 2026-11-25 · Five weeks, and the phone's own predictions

Results: [results/2026-11-25-live-predictions](../results/2026-11-25-live-predictions/summary.md),
[results/2026-11-25-learning-curve](../results/2026-11-25-learning-curve/summary.md),
[results/2026-11-25-frame-head](../results/2026-11-25-frame-head/summary.md) · export 10-20 – 11-24
(10922 reels, 5385 labeled first impressions), without 11-10, the evening spent testing gestures

Since 11-19 the app scores every reel itself, with weights fitted offline on the reels up to
11-09 (`autodoom.deploy`), and stores the probability with the reel. Six days of that: 1627
reels scored, 1001 of them labeled.

The phone against the offline code
- The same weights give the same answer: the two probabilities differ by 0.003 at the median
  and 0.022 at the 95th percentile, and 99% are within 0.05. The phone works from what it had
  read about 0.15 s into the reel and from the frame before it was saved; the offline code
  from the reel's final record and the JPEG. Which of the two the small gap comes from wasn't
  looked into.
- The probability is ready 150 ms into the reel at the median, 264 ms at the 95th percentile.
  32 ms of that comes after the first frame is captured (preparing it, the encoder, the
  model); the rest is the wait for the reel to settle on screen and the screenshot.

The phone against the user
- Skip AUC 0.704 (0.66–0.75) on days the model had never seen, against 0.657 for the same
  kind of model over the five weeks. By day: 0.77, 0.74, 0.70, 0.71, 0.75, 0.54. These were
  easy days as well as late ones: 38% of the labeled reels were skips, against 33% overall,
  and inside a viewing session the ranking is 0.64.
- At one threshold fixed in advance, P(skip) ≥ 0.8 would have skipped 86 reels, 92% of them
  rightly, a fifth of all skips. At 0.5: 168 reels, 80%, a third of all skips.
- Early on the same rule would have been a disaster. Scoring each day with a model fitted on
  the days before it: in the first two weeks, reels given 0.8 or more were skipped 44% of the
  time, and those given 0.9 or more 5%; in the last two weeks, 94% both (a one-off check, not
  kept). A young model's confidence means nothing.

How many days it takes
- With the last week as the test period and only k days before it to learn from, history
  included: 0.58 after one day, 0.70 after six, 0.71 after nine, and flat at 0.72 from three
  weeks on. Coverage at 80% precision goes from 6% to 28% in the first week.
- On that week the frame head gives nothing: with and without it the model is within 0.03 at
  every k, either way round, and equal from three weeks on. Over all five weeks it still adds
  +0.013 (+0.005 to +0.021) to context + creator (0.644), the same as at three weeks; CLIP
  +0.009; a caption head +0.005 (−0.004 to +0.013).

What it changes
- The phone's arithmetic is right and fast enough. The offline code is no longer needed to
  check it, only to analyse.
- Auto mode is on. The weight file carries its own threshold: the lowest P(skip) at which at
  least 90% of 30 or more reels were really skipped on the last 14 days, each scored by a
  model that hadn't seen them. On this data that is 0.70 (91% of 149, a fifth of the skips).
  Without such a point Auto mode stays locked, which is where a new user starts.
- A new user needs about a week before the model is worth acting on, and two more for its
  probabilities to be trusted. The threshold rule has to run on the phone, with the fitting.
- What a reel shows still matters little. Five weeks in, almost all of the signal is who
  posted it and how the session is going.

## 2026-11-25 · Battery cost

Result: [results/2026-11-25-battery](../results/2026-11-25-battery/summary.md) · script:
`ml/scripts/battery.py`, on runs taken the way `scripts/measure-battery.sh` takes them ·
Galaxy S24, Android 17

Four runs of ten minutes on battery, one after another: YouTube Shorts with a swipe to the
next one every 4 s (sent by script over Wi-Fi adb, so every run has the same pace), sound off,
brightness fixed, research mode and ad skip off. Read from `dumpsys batterystats`, reset at
the start of each run.

| Run | Discharge | Mean power | Battery per hour | App CPU | Skin temperature |
|---|---|---|---|---|---|
| App off | 66 mAh | 1.60 W | 9.8% | 0.2 s/min | 34.0 → 31.1 °C |
| Learning | 73 mAh | 1.77 W | 10.9% | 6.6 s/min | 30.9 → 31.2 °C |
| Learning + hands-free | 214 mAh | 5.01 W | 31.6% | 69.3 s/min | 31.7 → 41.7 °C |
| App off, again | 85 mAh | 2.00 W | 12.6% | 0.1 s/min | 41.1 → 33.5 °C |

- Learning costs little. It drew 0.17 W more than the first run with the app off, and the two
  runs with the app off differ by 0.40 W, so the difference is inside what two runs of the
  same setting give. The firm number is processor time: 6.6 s per minute, a ninth of one
  core, for reading the screen, the screenshots and the encoder.
- Hands-free costs three times the whole phone: 5.0 W, more than one core busy all the time
  (69 s of processor time per minute), and the skin temperature up by 10 °C in ten minutes.
  The face was in view throughout, so both models ran on every frame.
- The second run with the app off started at 41 °C, straight after hands-free, and is not a
  clean baseline; it shows how much the order of runs matters.
- The system's own estimate of the app's share (8.7 mA in Learning, 169 mA with hands-free)
  counts its processor time only. The feed app's estimate is the same 350–356 mA in all
  four runs, which says the pace was.
- Auto mode was not measured. It does what Learning does and adds a swipe.

What it changes
- Learning and Auto can stay on for a session without the user noticing in the battery.
- Hands-free can't, as it is. It has to analyse fewer frames, or run one model and wake the
  other only when needed, before it is more than a demonstration.
- Still owed: the runs in another order with the phone cooled in between, and Instagram.
