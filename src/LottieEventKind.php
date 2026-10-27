<?php

declare(strict_types=1);

namespace Pam\Native\Lottie;

/** `event` field of the native payloads sent by `lottie.view`. */
enum LottieEventKind: int
{
    case Loaded = 1;
    case Failed = 2;
    case Finished = 3;
}
