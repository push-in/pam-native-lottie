<?php

declare(strict_types=1);

namespace Pam\Native\Lottie;

/** How the composition fits the view (RN `resizeMode`). */
enum LottieResizeMode: int
{
    case Contain = 1;
    case Cover = 2;
    case Center = 3;
}
