<?php

declare(strict_types=1);

namespace Pam\Native\Lottie;

/** Where the animation JSON comes from. */
enum LottieSourceKind: int
{
    case Url = 1;
    case Json = 2;
}
