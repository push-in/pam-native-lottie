<?php

declare(strict_types=1);

namespace Pam\Native\Lottie\Internal;

use Closure;
use InvalidArgumentException;

/**
 * Validated animation files keyed by path + mtime + size, so a list that
 * renders the same bundled animation in every row reads and validates it
 * once. Bounded (oldest entry dropped first).
 *
 * @internal
 */
final class FileSource
{
    public const int MAX_ENTRIES = 32;

    /** @var array<string, string> */
    private static array $entries = [];

    private function __construct()
    {
    }

    /** @param Closure(string): string $validate */
    public static function read(string $path, Closure $validate): string
    {
        $stat = $path === '' ? false : @stat($path);
        if ($stat === false || !is_file($path)) {
            throw new InvalidArgumentException("Lottie file {$path} does not exist.");
        }
        $key = $path."\0".$stat['mtime']."\0".$stat['size'];
        if (isset(self::$entries[$key])) {
            $json = self::$entries[$key];
            unset(self::$entries[$key]);

            return self::$entries[$key] = $json;
        }
        $contents = file_get_contents($path);
        if ($contents === false) {
            throw new InvalidArgumentException("Lottie file {$path} could not be read.");
        }
        $json = $validate($contents);
        if (count(self::$entries) >= self::MAX_ENTRIES) {
            unset(self::$entries[array_key_first(self::$entries)]);
        }

        return self::$entries[$key] = $json;
    }

    public static function count(): int
    {
        return count(self::$entries);
    }

    public static function clear(): void
    {
        self::$entries = [];
    }
}
