# Browser extension (planned)

A Chrome extension that brings the same idea to `youtube.com/shorts` and
`instagram.com/reels` on desktop. Not started yet.

Planned design:

- **Content script** reads creator, caption and like state straight from the page, so no
  OCR is needed.
- **Exact watch ratio** from the `<video>` element's `duration` and `currentTime`, which
  gives cleaner labels than the Android app's loop detection.
- **Frames** via `chrome.tabs.captureVisibleTab`, since drawing a cross-origin video onto a
  canvas can taint it.
- **Models** trained in `ml/`, exported to ONNX or TF.js and run with ONNX Runtime Web or
  MediaPipe Tasks for Web, using the same feature definitions as the app.
