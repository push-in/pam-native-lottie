<?php

declare(strict_types=1);

use Pam\Native\Lottie\Lottie;

// A picker tile: only the first rows animate, the rest show the first frame.
return static fn (string $url, string $emoji, int $index): Lottie => Lottie::url($url)
    ->size(56)
    ->playing($index < 24)
    ->fallback($emoji);
