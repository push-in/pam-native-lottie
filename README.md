# pam-native-lottie

Native Lottie (Bodymovin) animations for PAM Native, the equivalent of
`lottie-react-native`: lottie-android 6.6 and lottie-ios 4.5 behind one host
view, `lottie.view`.

```php
use Pam\Native\Lottie\Lottie;

Lottie::url('https://cdn.example.com/party/lottie.json')
    ->size(116)
    ->playing($visible)   // RN ref.resume() / ref.pause()
    ->fallback('🎉');
```

```html
<Lottie :uri="$emoji['lottieUrl']" size="116" :playing="$visible" fallback="🎉" label="Emoji animado" />
<Lottie src="assets/lottie/success.json" size="64" :loop="false" />
```

- Frames only while the view is attached, visible and `playing`; reduced motion shows the first frame.
- One process-wide composition cache (32 entries / 12 MiB, LRU, trimmed on memory pressure);
  concurrent requests for a URL share a single download; parsing off the main thread.
- Android renders with `RenderMode.HARDWARE` (as `renderMode="HARDWARE"` in RN).

See [docs/lottie.md](docs/lottie.md). Install with `composer require pushinbr/pam-native-lottie`.
