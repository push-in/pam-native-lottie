# Lottie

`pushinbr/pam-native-lottie` renders Bodymovin/Lottie JSON natively.

## PHP

| Method | Default | Notes |
|---|---|---|
| `Lottie::url(string $uri)` | | `http(s)://`, `file://` or `asset://` (app bundle / Android assets). The URL is the cache key. |
| `Lottie::fromString(string $json)` | | Validated JSON object up to 2 MiB, keyed by its SHA-1. |
| `Lottie::fromFile(string $path)` | | Read and validated once per path + mtime (bounded). |
| `size(w, ?h)` | layout | Fixed size in dp. |
| `loop(bool)` | `true` | |
| `playing(bool)` / `autoPlay(bool)` | `true` | `false` pauses on the current frame; `true` resumes from it. A finished one-shot replays when `playing` goes false → true. |
| `speed(float)` | `1` | 0.05–8, negative plays backwards. |
| `resizeMode(LottieResizeMode)` | `Contain` | `Contain` = 1, `Cover` = 2, `Center` = 3. |
| `progress(?float)` | `null` | Pins the frame (0..1) and pauses. |
| `fallback(text, ?color, ?fontSize)` | | Native label shown until the animation is ready and when it fails. Font defaults to 60% of the smaller side. |
| `onLoad(fn(float $durationMs))`, `onError(fn(string))`, `onFinish(fn())` | | Native events (`LottieEventKind` Loaded = 1, Failed = 2, Finished = 3). |

## Template

`<Lottie src|uri|json size height loop playing autoPlay speed resizeMode progress fallback fallbackColor fallbackSize label class testId />`

## Resources

- No frame is produced while the view is detached, hidden (Android aggregated visibility; iOS
  window/isHidden/alpha) or paused; reduced motion (Android animator scale 0, iOS Reduce Motion)
  shows the first frame.
- Process-wide composition LRU: 32 entries / 12 MiB (estimated as 2× the JSON size); half kept on
  `TRIM_MEMORY_RUNNING_LOW`, emptied in background / on iOS memory warnings.
- Downloads: 2 MiB cap, 10 s connect / 15 s read, two low-priority threads; one load per key.
- Animate only what is on screen: in grids, pass `playing` only for the first visible tiles (a
  real-world app animates the first 24 tiles of its picker and shows the first frame elsewhere).
