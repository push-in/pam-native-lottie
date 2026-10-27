import Foundation
import Lottie
import PamNative
import UIKit

/// Registers `lottie.view`.
public final class LottieViewFactory: NativeViewFactory, @unchecked Sendable {
    private static let memoryObserver: NSObjectProtocol = NotificationCenter.default.addObserver(
        forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: .main
    ) { _ in
        LottieLoader.shared.trim(keeping: 0)
    }

    public init() {
        _ = Self.memoryObserver
    }

    public func create(context: AnyObject?, emit: @escaping (Data) -> Void) -> UIView {
        PamLottieView(emit: emit)
    }

    public func update(view: UIView, properties: [String: WireValue]) {
        (view as? PamLottieView)?.update(LottieProps(properties))
    }

    public func release(view: UIView) {
        (view as? PamLottieView)?.releaseResources()
    }
}

/// Host properties of one `lottie.view` (see `Lottie::hostProperties()`).
struct LottieProps {
    static let minSpeed = 0.05
    static let maxSpeed = 8.0

    var kind = LottieIdl.sourceUrl
    var source = ""
    var key = ""
    var loop = true
    var playing = true
    var speed = 1.0
    var resize = LottieIdl.resizeContain
    var progress: Double?
    var fallback = ""
    var fallbackColor: UIColor?
    var fallbackSize: Double?
    var events = false

    var hasSource: Bool { !source.isEmpty && !key.isEmpty }

    init(_ values: [String: WireValue]) {
        func text(_ name: String) -> String? { if case let .text(value)? = values[name] { return value }; return nil }
        func flag(_ name: String) -> Bool? { if case let .flag(value)? = values[name] { return value }; return nil }
        func number(_ name: String) -> Double? {
            switch values[name] {
            case let .integer(value)?: return Double(value)
            case let .decimal(value)?: return value
            default: return nil
            }
        }
        if let value = number("kind").map(Int.init), value == LottieIdl.sourceUrl || value == LottieIdl.sourceJson { kind = value }
        source = text("source") ?? ""
        key = text("key") ?? source
        loop = flag("loop") ?? true
        playing = flag("playing") ?? true
        if let value = number("speed"), value.isFinite, value != 0 {
            speed = value < 0 ? min(max(value, -Self.maxSpeed), -Self.minSpeed) : min(max(value, Self.minSpeed), Self.maxSpeed)
        }
        if let value = number("resize").map(Int.init), (LottieIdl.resizeContain...LottieIdl.resizeCenter).contains(value) { resize = value }
        progress = number("progress").flatMap { $0.isFinite ? min(max($0, 0), 1) : nil }
        fallback = text("fallback") ?? ""
        fallbackColor = text("fallbackColor").flatMap(lottieColor).map { UIColor(red: $0.0, green: $0.1, blue: $0.2, alpha: $0.3) }
        fallbackSize = number("fallbackSize").flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
        events = flag("events") ?? false
    }
}

enum LottieLoad {
    case ready(LottieAnimation)
    case failed(String)
}

/// Process-wide animation cache and loader: an LRU bounded by count and
/// estimated bytes, trimmed on memory warnings; downloads and decoding run
/// off the main thread and concurrent requests for a key share one load.
/// Public calls and callbacks are on the main thread.
final class LottieLoader {
    static let shared = LottieLoader()
    static let maxEntries = 32
    static let maxBytes = 12 * 1024 * 1024
    static let maxJsonBytes = 2 * 1024 * 1024

    private let cache = LottieLRU<LottieAnimation>(maxEntries: LottieLoader.maxEntries, maxBytes: LottieLoader.maxBytes)
    private let inFlight = LottieInFlight<LottieLoad>()
    private let decodeQueue = DispatchQueue(label: "dev.pam.lottie.decode", qos: .utility, attributes: .concurrent)
    private let session: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 15
        configuration.httpMaximumConnectionsPerHost = 2
        return URLSession(configuration: configuration)
    }()

    func cached(_ key: String) -> LottieAnimation? { cache[key] }

    func load(_ props: LottieProps, _ callback: @escaping (LottieLoad) -> Void) {
        if let animation = cache[props.key] {
            callback(.ready(animation))
            return
        }
        guard inFlight.join(props.key, callback) else { return }
        let key = props.key
        if props.kind == LottieIdl.sourceJson {
            decode(key: key, data: Data(props.source.utf8))
            return
        }
        guard let url = URL(string: props.source), let scheme = url.scheme?.lowercased() else {
            finish(key, .failed("Invalid Lottie URL."))
            return
        }
        switch scheme {
        case "http", "https":
            session.dataTask(with: url) { [weak self] data, response, error in
                guard let self else { return }
                let status = (response as? HTTPURLResponse)?.statusCode ?? 200
                if let data, error == nil, (200...299).contains(status), data.count <= Self.maxJsonBytes {
                    self.decode(key: key, data: data)
                } else {
                    let message = error?.localizedDescription ?? (status >= 300 ? "HTTP \(status)" : "Lottie JSON too large.")
                    DispatchQueue.main.async { self.finish(key, .failed(message)) }
                }
            }.resume()
        case "file", "asset":
            let path = scheme == "file" ? url.path : Bundle.main.path(forResource: (url.host ?? "") + url.path, ofType: nil) ?? ""
            decodeQueue.async { [weak self] in
                guard let self else { return }
                guard let data = FileManager.default.contents(atPath: path), data.count <= Self.maxJsonBytes else {
                    DispatchQueue.main.async { self.finish(key, .failed("Lottie file could not be read.")) }
                    return
                }
                self.decode(key: key, data: data)
            }
        default:
            finish(key, .failed("Unsupported Lottie URL scheme."))
        }
    }

    func trim(keeping fraction: Double) {
        if Thread.isMainThread { cache.trim(keeping: fraction) } else { DispatchQueue.main.async { self.cache.trim(keeping: fraction) } }
    }

    private func decode(key: String, data: Data) {
        decodeQueue.async { [weak self] in
            let result: LottieLoad
            do {
                result = .ready(try LottieAnimation.from(data: data))
            } catch {
                result = .failed(error.localizedDescription)
            }
            DispatchQueue.main.async {
                guard let self else { return }
                if case let .ready(animation) = result {
                    self.cache.put(key, animation, bytes: lottieAnimationBytes(jsonLength: data.count))
                }
                self.finish(key, result)
            }
        }
    }

    private func finish(_ key: String, _ result: LottieLoad) {
        inFlight.complete(key, result)
    }
}

/// One Lottie animation (`lottie-react-native` LottieView equivalent).
///
/// Frames run only while the view is in a window, not hidden, not fully
/// transparent and `playing`; otherwise it pauses on the current frame and
/// `play()` resumes from there (RN `ref.resume()`). With Reduce Motion the
/// first frame is shown. A fallback label covers the view until the
/// animation is ready and when it fails.
final class PamLottieView: UIView {
    private let emit: (Data) -> Void
    private let animationView: LottieAnimationView = {
        let view = LottieAnimationView(animation: nil, configuration: LottieConfiguration(renderingEngine: .automatic))
        view.backgroundBehavior = .pauseAndRestore
        view.isAccessibilityElement = false
        view.isHidden = true
        return view
    }()
    private let fallbackLabel: UILabel = {
        let label = UILabel()
        label.textAlignment = .center
        label.isAccessibilityElement = false
        label.isHidden = true
        return label
    }()
    private var props: LottieProps?
    private var loadedKey: String?
    private var generation = 0
    private var loaded = false
    private var failed = false
    private var finished = false
    private var reduceMotionObserver: NSObjectProtocol?

    init(emit: @escaping (Data) -> Void) {
        self.emit = emit
        super.init(frame: .zero)
        clipsToBounds = true
        addSubview(animationView)
        addSubview(fallbackLabel)
        reduceMotionObserver = NotificationCenter.default.addObserver(
            forName: UIAccessibility.reduceMotionStatusDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.applyPlayback()
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    deinit {
        if let reduceMotionObserver { NotificationCenter.default.removeObserver(reduceMotionObserver) }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        animationView.frame = bounds
        fallbackLabel.frame = bounds
        if props?.fallbackSize == nil {
            fallbackLabel.font = .systemFont(ofSize: max(1, min(bounds.width, bounds.height) * 0.6))
        }
    }

    func update(_ next: LottieProps) {
        let previous = props
        props = next
        if let previous, !previous.playing, next.playing, finished {
            finished = false
            animationView.currentProgress = next.speed < 0 ? 1 : 0
        }
        animationView.loopMode = next.loop ? .loop : .playOnce
        animationView.animationSpeed = CGFloat(next.speed)
        switch next.resize {
        case LottieIdl.resizeCover: animationView.contentMode = .scaleAspectFill
        case LottieIdl.resizeCenter: animationView.contentMode = .center
        default: animationView.contentMode = .scaleAspectFit
        }
        fallbackLabel.text = next.fallback
        if let color = next.fallbackColor { fallbackLabel.textColor = color }
        if let size = next.fallbackSize { fallbackLabel.font = .systemFont(ofSize: CGFloat(size)) }
        if next.key != loadedKey { load(next) } else { applyPlayback() }
        updateFallback()
    }

    private func load(_ next: LottieProps) {
        generation += 1
        let token = generation
        loadedKey = next.key
        loaded = false
        failed = false
        finished = false
        animationView.stop()
        animationView.animation = nil
        animationView.isHidden = true
        guard next.hasSource else {
            failed = true
            return
        }
        LottieLoader.shared.load(next) { [weak self] result in
            guard let self, token == self.generation else { return }
            switch result {
            case let .ready(animation):
                self.animationView.animation = animation
                self.animationView.isHidden = false
                self.loaded = true
                if self.props?.events == true {
                    self.send(LottieIdl.eventLoaded, ["durationMs": .decimal(animation.duration * 1_000)])
                }
                self.applyPlayback()
            case let .failed(message):
                self.failed = true
                if self.props?.events == true { self.send(LottieIdl.eventFailed, ["message": .text(message)]) }
            }
            self.updateFallback()
        }
    }

    private var onScreen: Bool {
        window != nil && !isHidden && alpha > 0.01 && !(superview?.isHidden ?? true)
    }

    private func applyPlayback() {
        guard let props, loaded else { return }
        switch lottiePlayback(
            playing: props.playing,
            progress: props.progress,
            onScreen: onScreen,
            reduceMotion: UIAccessibility.isReduceMotionEnabled,
            finished: finished
        ) {
        case .pin:
            if animationView.isAnimationPlaying { animationView.pause() }
            animationView.currentProgress = CGFloat(props.progress ?? 0)
        case .firstFrame:
            if animationView.isAnimationPlaying { animationView.pause() }
            animationView.currentProgress = props.speed < 0 ? 1 : 0
        case .run:
            guard !animationView.isAnimationPlaying else { return }
            let token = generation
            animationView.play { [weak self] completed in
                guard let self, completed, token == self.generation, let props = self.props, !props.loop else { return }
                self.finished = true
                if props.events { self.send(LottieIdl.eventFinished, [:]) }
            }
        case .pause:
            if animationView.isAnimationPlaying { animationView.pause() }
        }
    }

    private func updateFallback() {
        fallbackLabel.isHidden = !((!loaded || failed) && !(fallbackLabel.text ?? "").isEmpty)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        applyPlayback()
    }

    override var isHidden: Bool {
        didSet { if oldValue != isHidden { applyPlayback() } }
    }

    override var alpha: CGFloat {
        didSet { if (oldValue > 0.01) != (alpha > 0.01) { applyPlayback() } }
    }

    func releaseResources() {
        generation += 1
        animationView.stop()
        animationView.animation = nil
        props = nil
        loadedKey = nil
        loaded = false
        finished = false
    }

    private func send(_ event: Int, _ extra: [String: WireValue]) {
        var values = extra
        values["event"] = .integer(Int64(event))
        if let payload = try? WireMap.encode(values) { emit(payload) }
    }
}
