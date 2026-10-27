import Foundation

/// `LottieSourceKind` / `LottieResizeMode` / `LottieEventKind` of the IDL (integers from 1).
enum LottieIdl {
    static let sourceUrl = 1
    static let sourceJson = 2

    static let resizeContain = 1
    static let resizeCover = 2
    static let resizeCenter = 3

    static let eventLoaded = 1
    static let eventFailed = 2
    static let eventFinished = 3
}

/// Least-recently-used map bounded by entry count and estimated bytes;
/// `trim(keeping:)` keeps a fraction of the budget (memory warning).
/// Main-thread only.
final class LottieLRU<Value> {
    private struct Entry {
        let value: Value
        let bytes: Int
    }

    private let maxEntries: Int
    private let maxBytes: Int
    private var entries: [String: Entry] = [:]
    /// Least recent first.
    private(set) var order: [String] = []
    private(set) var bytes = 0

    init(maxEntries: Int, maxBytes: Int) {
        self.maxEntries = maxEntries
        self.maxBytes = maxBytes
    }

    var count: Int { entries.count }

    subscript(key: String) -> Value? {
        guard let entry = entries[key] else { return nil }
        touch(key)
        return entry.value
    }

    func put(_ key: String, _ value: Value, bytes cost: Int) {
        let cost = max(1, cost)
        if let old = entries.removeValue(forKey: key) {
            bytes -= old.bytes
            order.removeAll { $0 == key }
        }
        guard cost <= maxBytes else { return }
        entries[key] = Entry(value: value, bytes: cost)
        order.append(key)
        bytes += cost
        evict(entryLimit: maxEntries, byteLimit: maxBytes)
    }

    func trim(keeping fraction: Double) {
        let fraction = min(max(fraction, 0), 1)
        evict(entryLimit: Int(Double(maxEntries) * fraction), byteLimit: Int(Double(maxBytes) * fraction))
    }

    private func touch(_ key: String) {
        if order.last == key { return }
        order.removeAll { $0 == key }
        order.append(key)
    }

    private func evict(entryLimit: Int, byteLimit: Int) {
        while (entries.count > entryLimit || bytes > byteLimit), !order.isEmpty {
            let key = order.removeFirst()
            if let entry = entries.removeValue(forKey: key) { bytes -= entry.bytes }
        }
    }
}

/// Callbacks waiting on one in-flight load; a second request joins the first.
final class LottieInFlight<Result> {
    private var waiting: [String: [(Result) -> Void]] = [:]

    /// True when the caller must start the load.
    func join(_ key: String, _ callback: @escaping (Result) -> Void) -> Bool {
        if waiting[key] != nil {
            waiting[key]?.append(callback)
            return false
        }
        waiting[key] = [callback]
        return true
    }

    func complete(_ key: String, _ result: Result) {
        waiting.removeValue(forKey: key)?.forEach { $0(result) }
    }

    func pending(_ key: String) -> Int { waiting[key]?.count ?? 0 }
}

enum LottiePlayback: Equatable {
    case run, pause, pin, firstFrame
}

func lottiePlayback(
    playing: Bool,
    progress: Double?,
    onScreen: Bool,
    reduceMotion: Bool,
    finished: Bool
) -> LottiePlayback {
    if progress != nil { return .pin }
    if reduceMotion { return .firstFrame }
    return playing && onScreen && !finished ? .run : .pause
}

/// `#rgb`, `#rrggbb` and CSS `#rrggbbaa` to (r, g, b, a) in 0...1.
func lottieColor(_ value: String) -> (Double, Double, Double, Double)? {
    var hex = value.trimmingCharacters(in: .whitespaces)
    if hex.hasPrefix("#") { hex.removeFirst() }
    guard !hex.isEmpty, hex.allSatisfy(\.isHexDigit), let raw = UInt64(hex, radix: 16) else { return nil }
    switch hex.count {
    case 3:
        return (Double((raw >> 8) & 0xF) / 15, Double((raw >> 4) & 0xF) / 15, Double(raw & 0xF) / 15, 1)
    case 6:
        return (Double((raw >> 16) & 0xFF) / 255, Double((raw >> 8) & 0xFF) / 255, Double(raw & 0xFF) / 255, 1)
    case 8:
        return (Double((raw >> 24) & 0xFF) / 255, Double((raw >> 16) & 0xFF) / 255, Double((raw >> 8) & 0xFF) / 255, Double(raw & 0xFF) / 255)
    default:
        return nil
    }
}

/// Estimated retained size of a parsed animation (the JSON dominates).
func lottieAnimationBytes(jsonLength: Int) -> Int { jsonLength * 2 + 4_096 }
