// macOS/iOS-SDK check of the shared Swift core (cache, in-flight joins, playback policy, colors).
// Run on a Mac from the package root:
//   swiftc -O ios/Sources/LottieCore.swift tests/ios-core/main.swift -o .build/lottie-core && .build/lottie-core
import Foundation

var failures = 0
func check(_ condition: @autoclosure () -> Bool, _ label: String) {
    if condition() { print("PASS \(label)") } else { failures += 1; print("FAIL \(label)") }
}

let lru = LottieLRU<String>(maxEntries: 2, maxBytes: 100)
lru.put("a", "A", bytes: 10)
lru.put("b", "B", bytes: 10)
_ = lru["a"]
lru.put("c", "C", bytes: 10)
check(lru.order == ["a", "c"], "count eviction keeps the recently used entry")
lru.put("d", "D", bytes: 95)
check(lru.order == ["d"] && lru.bytes == 95, "byte eviction")
lru.put("huge", "H", bytes: 101)
check(lru["huge"] == nil, "entries above the budget are not cached")
let trimmed = LottieLRU<String>(maxEntries: 4, maxBytes: 100)
for key in ["a", "b", "c", "d"] { trimmed.put(key, key, bytes: 20) }
trimmed.trim(keeping: 0.5)
check(trimmed.order == ["c", "d"], "memory warning keeps half")
trimmed.trim(keeping: 0)
check(trimmed.count == 0 && trimmed.bytes == 0, "trim to zero")

let inFlight = LottieInFlight<String>()
var seen: [String] = []
check(inFlight.join("k") { seen.append("1:\($0)") }, "first request loads")
check(!inFlight.join("k") { seen.append("2:\($0)") }, "second request joins")
inFlight.complete("k", "ok")
check(seen == ["1:ok", "2:ok"] && inFlight.pending("k") == 0, "both callbacks complete once")

check(lottiePlayback(playing: true, progress: nil, onScreen: true, reduceMotion: false, finished: false) == .run, "runs on screen")
check(lottiePlayback(playing: true, progress: nil, onScreen: false, reduceMotion: false, finished: false) == .pause, "pauses off screen")
check(lottiePlayback(playing: false, progress: nil, onScreen: true, reduceMotion: false, finished: false) == .pause, "pauses when not playing")
check(lottiePlayback(playing: true, progress: nil, onScreen: true, reduceMotion: false, finished: true) == .pause, "one-shot stays finished")
check(lottiePlayback(playing: true, progress: nil, onScreen: true, reduceMotion: true, finished: false) == .firstFrame, "reduced motion")
check(lottiePlayback(playing: true, progress: 0.5, onScreen: true, reduceMotion: false, finished: false) == .pin, "pinned progress")

check(lottieColor("#f00").map { $0.0 == 1 && $0.1 == 0 && $0.3 == 1 } ?? false, "#rgb")
check(lottieColor("#10203080").map { abs($0.3 - 128.0 / 255) < 1e-9 } ?? false, "#rrggbbaa")
check(lottieColor("red") == nil && lottieColor("#12345") == nil, "invalid colors")

print(failures == 0 ? "OK" : "\(failures) failures")
exit(failures == 0 ? 0 : 1)
