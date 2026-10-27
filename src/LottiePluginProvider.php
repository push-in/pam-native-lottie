<?php

declare(strict_types=1);

namespace Pam\Native\Lottie;

use Pam\Native\Plugin\PluginProvider;
use Pam\Native\TemplateRegistry;

/**
 * Registers the declarative tag:
 *
 * ```html
 * <Lottie src="assets/lottie/success.json" size="64" :loop="false" />
 * <Lottie :uri="$emoji['lottieUrl']" size="116" :playing="$visible" fallback="🎉" label="Emoji animado" />
 * ```
 *
 * Attributes: `src` (app-relative file) | `uri` (URL) | `json` (inline),
 * `size`/`height`, `loop`, `playing`/`autoPlay`, `speed`, `resizeMode`
 * (contain, cover, center), `progress`, `fallback`, `fallbackColor`,
 * `fallbackSize`, `label`, `class`, `testId`.
 */
final class LottiePluginProvider implements PluginProvider
{
    public function register(): void
    {
        TemplateRegistry::component('Lottie', static fn (array $props, array $_children, ?object $_scope): Lottie => self::build($props));
    }

    public function boot(): void
    {
    }

    /** @param array<string, mixed> $props */
    public static function build(array $props): Lottie
    {
        $uri = self::text($props['uri'] ?? null);
        $json = self::text($props['json'] ?? null);
        $lottie = match (true) {
            $uri !== null => Lottie::url($uri),
            $json !== null => Lottie::fromString($json),
            default => Lottie::fromFile(self::path(self::text($props['src'] ?? null) ?? '')),
        };
        $size = self::number($props['size'] ?? null);
        if ($size !== null) {
            $lottie = $lottie->size($size, self::number($props['height'] ?? null));
        }
        if (array_key_exists('loop', $props)) {
            $lottie = $lottie->loop(self::flag($props['loop']));
        }
        foreach (['autoPlay', 'playing'] as $name) {
            if (array_key_exists($name, $props)) {
                $lottie = $lottie->playing(self::flag($props[$name]));
            }
        }
        $speed = self::number($props['speed'] ?? null);
        if ($speed !== null) {
            $lottie = $lottie->speed($speed);
        }
        $mode = self::text($props['resizeMode'] ?? null);
        if ($mode !== null) {
            $lottie = $lottie->resizeMode(match (strtolower($mode)) {
                'cover' => LottieResizeMode::Cover,
                'center' => LottieResizeMode::Center,
                default => LottieResizeMode::Contain,
            });
        }
        if (array_key_exists('progress', $props)) {
            $lottie = $lottie->progress(self::number($props['progress']));
        }
        $fallback = self::text($props['fallback'] ?? null);
        if ($fallback !== null) {
            $lottie = $lottie->fallback($fallback, self::text($props['fallbackColor'] ?? null), self::number($props['fallbackSize'] ?? null));
        }
        $label = self::text($props['label'] ?? $props['accessibilityLabel'] ?? null);
        if ($label !== null) {
            $lottie = $lottie->accessibilityLabel($label);
        }
        $class = self::text($props['class'] ?? null);
        if ($class !== null && trim($class) !== '') {
            $lottie = $lottie->class(...preg_split('/\s+/', trim($class)) ?: []);
        }
        $testId = self::text($props['testId'] ?? null);

        return $testId === null ? $lottie : $lottie->testId($testId);
    }

    private static function path(string $path): string
    {
        if ($path === '' || str_starts_with($path, '/')) {
            return $path;
        }
        $base = defined('PAM_NATIVE_APP_ROOT') && is_string(constant('PAM_NATIVE_APP_ROOT'))
            ? constant('PAM_NATIVE_APP_ROOT')
            : (getcwd() ?: '.');

        return rtrim($base, '/').'/'.$path;
    }

    private static function text(mixed $value): ?string
    {
        return is_string($value) && $value !== '' ? $value : null;
    }

    private static function number(mixed $value): ?float
    {
        return is_int($value) || is_float($value) || (is_string($value) && is_numeric($value)) ? (float) $value : null;
    }

    private static function flag(mixed $value): bool
    {
        return is_bool($value) ? $value : filter_var($value, FILTER_VALIDATE_BOOL, FILTER_NULL_ON_FAILURE) ?? ($value === '');
    }
}
