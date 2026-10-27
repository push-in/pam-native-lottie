<?php

declare(strict_types=1);

namespace Pam\Native\Lottie;

use Closure;
use InvalidArgumentException;
use Pam\Native\Element;
use Pam\Native\Internal\Wire;
use Pam\Native\Lottie\Internal\FileSource;
use Pam\Native\Renderable;
use Pam\Native\Style;
use Pam\Native\UI\CustomView;

/**
 * Native Lottie animation rendered by the `lottie.view` host view
 * (lottie-android / lottie-ios, the engines behind `lottie-react-native`).
 *
 * ```php
 * Lottie::url('https://cdn.example.com/party/lottie.json')
 *     ->size(116)->loop()->playing($rowVisible)->fallback('🎉');
 * Lottie::fromFile(__DIR__.'/assets/success.json')->size(64)->loop(false);
 * ```
 *
 * `playing()` is the imperative play/pause of RN's `ref.resume()` /
 * `ref.pause()`: flipping it resumes from the current frame instead of
 * restarting. The native view never draws frames while it is detached,
 * hidden or scrolled out of its window, and with reduced motion it shows the
 * first frame. Compositions are parsed once per URL (or content hash) into a
 * process-wide cache bounded by count and bytes and trimmed on memory
 * pressure; concurrent requests for the same URL share one download.
 */
final class Lottie implements Renderable
{
    public const string NAME = 'lottie.view';
    /** Largest inline JSON accepted (the native fetch of a URL is bounded the same way). */
    public const int MAX_JSON_BYTES = 2_097_152;
    public const float MIN_SPEED = 0.05;
    public const float MAX_SPEED = 8.0;

    private ?float $layoutWidth = null;
    private ?float $layoutHeight = null;
    private bool $loop = true;
    private bool $playing = true;
    private float $speed = 1.0;
    private LottieResizeMode $resizeMode = LottieResizeMode::Contain;
    private ?float $progress = null;
    private string $fallback = '';
    private ?string $fallbackColor = null;
    private ?float $fallbackSize = null;
    private ?string $label = null;
    private ?string $testId = null;

    /** @var list<string> */
    private array $classes = [];

    /** @var array<int, Closure> */
    private array $handlers = [];

    private function __construct(
        private readonly LottieSourceKind $kind,
        private readonly string $source,
        private readonly string $key,
    ) {
    }

    /** Animation JSON downloaded natively (http, https, file or asset URL). */
    public static function url(string $uri): self
    {
        $uri = trim($uri);
        if (preg_match('~^(https?|file|asset)://\S+$~i', $uri) !== 1 || strlen($uri) > 4_096) {
            throw new InvalidArgumentException('Lottie URLs must be absolute http(s), file or asset URLs.');
        }

        return new self(LottieSourceKind::Url, $uri, $uri);
    }

    /** Animation JSON passed inline; the native side parses each content hash once. */
    public static function fromString(string $json): self
    {
        $json = self::validate($json);

        return new self(LottieSourceKind::Json, $json, 'sha1:'.sha1($json));
    }

    /** Reads (and validates once per path + mtime) a bundled animation JSON file. */
    public static function fromFile(string $path): self
    {
        return self::fromString(FileSource::read($path, self::validate(...)));
    }

    public function size(float $width, ?float $height = null): self
    {
        if (!is_finite($width) || $width <= 0.0 || ($height !== null && (!is_finite($height) || $height <= 0.0))) {
            throw new InvalidArgumentException('Lottie sizes must be positive.');
        }
        $this->layoutWidth = $width;
        $this->layoutHeight = $height ?? $width;

        return $this;
    }

    public function loop(bool $loop = true): self
    {
        $this->loop = $loop;

        return $this;
    }

    /** Plays (resumes) or pauses on the current frame; true by default (RN `autoPlay`). */
    public function playing(bool $playing = true): self
    {
        $this->playing = $playing;

        return $this;
    }

    /** Alias of {@see playing()} for code ported from `autoPlay`. */
    public function autoPlay(bool $autoPlay = true): self
    {
        return $this->playing($autoPlay);
    }

    public function speed(float $speed): self
    {
        if (!is_finite($speed) || abs($speed) < self::MIN_SPEED || abs($speed) > self::MAX_SPEED) {
            throw new InvalidArgumentException('Lottie speed must be between 0.05 and 8 (negative plays backwards).');
        }
        $this->speed = $speed;

        return $this;
    }

    public function resizeMode(LottieResizeMode $mode): self
    {
        $this->resizeMode = $mode;

        return $this;
    }

    /** Pins the animation at [progress] (0..1) and pauses it; null releases it. */
    public function progress(?float $progress): self
    {
        if ($progress !== null && !is_finite($progress)) {
            throw new InvalidArgumentException('Lottie progress must be finite.');
        }
        $this->progress = $progress === null ? null : max(0.0, min(1.0, $progress));

        return $this;
    }

    /** Text drawn natively until the composition loads, and when it fails. */
    public function fallback(string $text, ?string $color = null, ?float $fontSize = null): self
    {
        if (mb_strlen($text) > 64) {
            throw new InvalidArgumentException('Lottie fallbacks are short labels (at most 64 characters).');
        }
        if ($fontSize !== null && (!is_finite($fontSize) || $fontSize <= 0.0)) {
            throw new InvalidArgumentException('Lottie fallback font sizes must be positive.');
        }
        $this->fallback = $text;
        $this->fallbackColor = $color;
        $this->fallbackSize = $fontSize;

        return $this;
    }

    public function class(string ...$classes): self
    {
        $this->classes = [...$this->classes, ...$classes];

        return $this;
    }

    public function accessibilityLabel(string $label): self
    {
        $this->label = $label;

        return $this;
    }

    public function testId(string $id): self
    {
        $this->testId = $id;

        return $this;
    }

    /** @param Closure(float): void $handler receives the duration in milliseconds */
    public function onLoad(Closure $handler): self
    {
        $this->handlers[LottieEventKind::Loaded->value] = $handler;

        return $this;
    }

    /** @param Closure(string): void $handler */
    public function onError(Closure $handler): self
    {
        $this->handlers[LottieEventKind::Failed->value] = $handler;

        return $this;
    }

    /** Fired when a non-looping animation reaches its end. */
    public function onFinish(Closure $handler): self
    {
        $this->handlers[LottieEventKind::Finished->value] = $handler;

        return $this;
    }

    public function cacheKey(): string
    {
        return $this->key;
    }

    /** @return array<string, string|int|float|bool> */
    public function hostProperties(): array
    {
        $properties = [
            'kind' => $this->kind->value,
            'source' => $this->source,
            'key' => $this->key,
            'loop' => $this->loop,
            'playing' => $this->playing,
            'speed' => $this->speed,
            'resize' => $this->resizeMode->value,
            'events' => $this->handlers !== [],
        ];
        if ($this->progress !== null) {
            $properties['progress'] = $this->progress;
        }
        if ($this->fallback !== '') {
            $properties['fallback'] = $this->fallback;
        }
        if ($this->fallbackColor !== null) {
            $properties['fallbackColor'] = $this->fallbackColor;
        }
        if ($this->fallbackSize !== null) {
            $properties['fallbackSize'] = $this->fallbackSize;
        }

        return $properties;
    }

    public function toElement(): Element
    {
        $view = CustomView::make(self::NAME, $this->hostProperties());
        if ($this->layoutWidth !== null) {
            $view = $view->style(new Style(width: $this->layoutWidth, height: $this->layoutHeight));
        }
        if ($this->classes !== []) {
            $view = $view->class(...$this->classes);
        }
        if ($this->label !== null) {
            $view = $view->accessibilityLabel($this->label);
        }
        if ($this->testId !== null) {
            $view = $view->testId($this->testId);
        }
        if ($this->handlers === []) {
            return $view;
        }
        $handlers = $this->handlers;

        return $view->onNativeEvent(static function (string $payload) use ($handlers): void {
            self::dispatch(Wire::decodeMap($payload), $handlers);
        });
    }

    /**
     * @internal
     * @param array<string, mixed> $values
     * @param array<int, Closure> $handlers
     */
    public static function dispatch(array $values, array $handlers): void
    {
        $kind = LottieEventKind::tryFrom((int) ($values['event'] ?? 0));
        $handler = $kind === null ? null : ($handlers[$kind->value] ?? null);
        if ($handler === null) {
            return;
        }
        match ($kind) {
            LottieEventKind::Loaded => $handler((float) ($values['durationMs'] ?? 0)),
            LottieEventKind::Failed => $handler((string) ($values['message'] ?? '')),
            LottieEventKind::Finished => $handler(),
        };
    }

    private static function validate(string $json): string
    {
        $json = trim($json);
        if ($json === '' || strlen($json) > self::MAX_JSON_BYTES) {
            throw new InvalidArgumentException('Lottie JSON must be between 1 byte and 2 MiB.');
        }
        if ($json[0] !== '{' || !json_validate($json)) {
            throw new InvalidArgumentException('Lottie sources must be a JSON object (Bodymovin export).');
        }

        return $json;
    }
}
