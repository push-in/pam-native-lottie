<?php

declare(strict_types=1);

// Contract tests for pushinbr/pam-native-lottie. Run with `pam tests/run.php`
// (or `php tests/run.php` with PAM_NATIVE_SRC pointing at the framework sources).

require __DIR__.'/bootstrap.php';

use Pam\Native\Element;
use Pam\Native\Lottie\Internal\FileSource;
use Pam\Native\Lottie\Lottie;
use Pam\Native\Lottie\LottieEventKind;
use Pam\Native\Lottie\LottiePluginProvider;
use Pam\Native\Lottie\LottieResizeMode;
use Pam\Native\Lottie\LottieSourceKind;
use Pam\Native\TemplateRegistry;

$tests = [];
$test = static function (string $name, Closure $body) use (&$tests): void {
    $tests[$name] = $body;
};
$assert = static function (bool $condition, string $message): void {
    if (!$condition) {
        throw new RuntimeException($message);
    }
};
$throws = static function (Closure $body, string $message): void {
    try {
        $body();
    } catch (InvalidArgumentException) {
        return;
    }
    throw new RuntimeException($message);
};
$fixture = __DIR__.'/fixtures/pulse.json';
$frameworkLoaded = class_exists(Element::class);

$test('enums are sequential integers from 1 and match the IDL', static function () use ($assert): void {
    $idl = json_decode((string) file_get_contents(dirname(__DIR__).'/pam-native.idl.json'), true, flags: JSON_THROW_ON_ERROR);
    foreach ([LottieSourceKind::class, LottieResizeMode::class, LottieEventKind::class] as $enum) {
        $short = substr($enum, strrpos($enum, '\\') + 1);
        $values = array_map(static fn ($case): int => $case->value, $enum::cases());
        $assert($values === range(1, count($values)), "{$short} must start at 1 without gaps");
        $names = array_map(static fn ($case): string => $case->name, $enum::cases());
        $assert(array_combine($names, $values) === $idl['enums'][$short], "{$short} differs from the IDL");
    }
});

$test('url sources travel as the URL and use it as cache key', static function () use ($assert): void {
    $lottie = Lottie::url(' https://cdn.example.com/1f389/lottie.json ');
    $props = $lottie->hostProperties();
    $assert($props['kind'] === LottieSourceKind::Url->value, 'kind');
    $assert($props['source'] === 'https://cdn.example.com/1f389/lottie.json', 'trimmed url');
    $assert($props['key'] === $props['source'], 'url key');
    $assert($props['loop'] === true && $props['playing'] === true && $props['speed'] === 1.0, 'RN defaults: loop + autoPlay');
    $assert($props['resize'] === LottieResizeMode::Contain->value, 'contain by default');
    $assert(!array_key_exists('progress', $props) && !array_key_exists('fallback', $props), 'optional props omitted');
    $assert($props['events'] === false, 'no events without handlers');
});

$test('rejects unsafe or relative URLs', static function () use ($throws): void {
    $throws(static fn () => Lottie::url('javascript:alert(1)'), 'javascript scheme');
    $throws(static fn () => Lottie::url('lottie.json'), 'relative path');
    $throws(static fn () => Lottie::url('https://a b/x.json'), 'whitespace');
});

$test('inline JSON is validated and keyed by content hash', static function () use ($assert, $throws, $fixture): void {
    $json = (string) file_get_contents($fixture);
    $a = Lottie::fromString($json."\n");
    $b = Lottie::fromString($json);
    $assert($a->cacheKey() === $b->cacheKey() && str_starts_with($a->cacheKey(), 'sha1:'), 'stable hash key');
    $assert($a->hostProperties()['kind'] === LottieSourceKind::Json->value, 'json kind');
    $throws(static fn () => Lottie::fromString('[1,2]'), 'array root');
    $throws(static fn () => Lottie::fromString('{"v":'), 'broken json');
    $throws(static fn () => Lottie::fromString('{"p":"'.str_repeat('a', Lottie::MAX_JSON_BYTES).'"}'), 'size limit');
});

$test('fromFile reads and validates each file version once', static function () use ($assert, $throws, $fixture): void {
    FileSource::clear();
    $first = Lottie::fromFile($fixture);
    $second = Lottie::fromFile($fixture);
    $assert($first->cacheKey() === $second->cacheKey(), 'same key');
    $assert(FileSource::count() === 1, 'one cached entry');
    $throws(static fn () => Lottie::fromFile(__DIR__.'/fixtures/missing.json'), 'missing file');
    FileSource::clear();
});

$test('playback, speed, resize, progress and fallback props', static function () use ($assert, $throws): void {
    $props = Lottie::url('https://cdn.example.com/a.json')
        ->loop(false)->playing(false)->speed(-1.5)->resizeMode(LottieResizeMode::Cover)
        ->progress(1.4)->fallback('🎉', '#ffffff', 40)->hostProperties();
    $assert($props['loop'] === false && $props['playing'] === false, 'loop/playing');
    $assert($props['speed'] === -1.5 && $props['resize'] === 2, 'speed/resize');
    $assert($props['progress'] === 1.0, 'progress clamped');
    $assert($props['fallback'] === '🎉' && $props['fallbackColor'] === '#ffffff' && $props['fallbackSize'] === 40.0, 'fallback');
    $assert(Lottie::url('https://x.dev/a.json')->autoPlay(false)->hostProperties()['playing'] === false, 'autoPlay alias');
    $assert(!array_key_exists('progress', Lottie::url('https://x.dev/a.json')->progress(0.5)->progress(null)->hostProperties()), 'progress released');
    $throws(static fn () => Lottie::url('https://x.dev/a.json')->speed(0.0), 'zero speed');
    $throws(static fn () => Lottie::url('https://x.dev/a.json')->speed(20.0), 'fast speed');
    $throws(static fn () => Lottie::url('https://x.dev/a.json')->size(0), 'zero size');
    $throws(static fn () => Lottie::url('https://x.dev/a.json')->fallback(str_repeat('x', 65)), 'long fallback');
});

$test('native events dispatch to the matching handler only', static function () use ($assert): void {
    $seen = [];
    $handlers = [
        LottieEventKind::Loaded->value => static function (float $ms) use (&$seen): void { $seen[] = "loaded:{$ms}"; },
        LottieEventKind::Failed->value => static function (string $message) use (&$seen): void { $seen[] = "failed:{$message}"; },
        LottieEventKind::Finished->value => static function () use (&$seen): void { $seen[] = 'finished'; },
    ];
    Lottie::dispatch(['event' => 1, 'durationMs' => 1000], $handlers);
    Lottie::dispatch(['event' => 2, 'message' => 'HTTP 404'], $handlers);
    Lottie::dispatch(['event' => 3], $handlers);
    Lottie::dispatch(['event' => 9], $handlers);
    Lottie::dispatch(['event' => 3], []);
    $assert($seen === ['loaded:1000', 'failed:HTTP 404', 'finished'], implode(',', $seen));
    $assert(Lottie::url('https://x.dev/a.json')->onFinish(static fn () => null)->hostProperties()['events'] === true, 'events flag');
});

if ($frameworkLoaded) {
    $test('renders a lottie.view custom view with layout and accessibility', static function () use ($assert): void {
        $element = Lottie::url('https://x.dev/a.json')->size(116)->accessibilityLabel('Emoji animado')
            ->onLoad(static fn (float $_ms) => null)->toElement();
        $assert($element instanceof Element, 'element');
    });

    $test('registers the Lottie template tag', static function () use ($assert, $fixture): void {
        TemplateRegistry::reset();
        (new LottiePluginProvider())->register();
        $factory = TemplateRegistry::factory('Lottie');
        $assert($factory instanceof Closure, 'tag missing');
        $remote = $factory(['uri' => 'https://x.dev/a.json', 'size' => '116', 'playing' => 'false', 'resizeMode' => 'cover', 'fallback' => '🙂', 'label' => 'Emoji'], [], null);
        $props = $remote->hostProperties();
        $assert($remote instanceof Lottie && $props['playing'] === false && $props['resize'] === 2 && $props['fallback'] === '🙂', 'uri tag');
        $local = $factory(['src' => $fixture, 'loop' => false, 'speed' => '2'], [], null);
        $assert($local->hostProperties()['kind'] === 2 && $local->hostProperties()['loop'] === false && $local->hostProperties()['speed'] === 2.0, 'src tag');
        $assert($factory(['json' => '{"v":"5"}', 'progress' => '0.25'], [], null)->hostProperties()['progress'] === 0.25, 'json tag');
        TemplateRegistry::reset();
    });
}

$failed = 0;
foreach ($tests as $name => $body) {
    try {
        $body();
        fwrite(STDOUT, "PASS {$name}\n");
    } catch (Throwable $error) {
        $failed++;
        fwrite(STDERR, "FAIL {$name}: {$error->getMessage()}\n");
    }
}
fwrite(STDOUT, count($tests).' tests, '.$failed." failures\n");
exit($failed > 0 ? 1 : 0);
