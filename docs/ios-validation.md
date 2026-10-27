# iOS validation checklist

1. Core harness (no Lottie dependency), from the package root on a Mac:
   `mkdir -p .build && swiftc -O ios/Sources/LottieCore.swift tests/ios-core/main.swift -o .build/lottie-core && .build/lottie-core`
2. App build with the plugin: `lottie-spm` resolves (product `Lottie`), `LottieViewFactory` registers `lottie.view`.
3. On the Simulator: a remote animation plays, pauses when `playing=false` and resumes from the same frame;
   scrolling it off-window stops frames (Instruments: no CADisplayLink ticks); Reduce Motion shows frame 0;
   a 404 URL shows the fallback and fires `onError`; a one-shot fires `onFinish` once.
