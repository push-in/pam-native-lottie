# Changelog

## 0.1.0 - 2026-10-27

- New package: native Lottie animations through the `lottie.view` host view
  (lottie-android 6.6.0, lottie-ios 4.5 via lottie-spm).
- `Lottie::url()` (http, https, file, asset), `Lottie::fromString()` and `Lottie::fromFile()`
  (JSON validated once per path + mtime); `size()`, `loop()`, `playing()`/`autoPlay()`, `speed()`,
  `resizeMode()` (contain, cover, center), `progress()`, `fallback()`, `onLoad()`, `onError()`,
  `onFinish()`; `<Lottie>` template tag.
- Lean by default: frames only while attached, visible and playing (pause keeps the frame, play
  resumes it); reduced motion shows the first frame; composition LRU bounded by count and bytes and
  trimmed on memory pressure; one shared load per URL; parsing on background threads.
- Tests: 9 PHP contracts, 8 JVM unit tests (props, colors, LRU, in-flight joins, playback policy),
  iOS core harness.
- Android lint clean with warnings as errors (`toUri()`, programmatic-only view);
  compiled against the PAM Native host build on Android and for the iOS Simulator
  (lottie-spm resolves `from: 4.5.0`, currently 4.6.1).
