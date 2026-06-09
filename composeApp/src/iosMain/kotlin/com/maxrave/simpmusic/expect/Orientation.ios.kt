package com.maxrave.simpmusic.expect

import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation

actual fun currentOrientation(): Orientation =
    when (UIDevice.currentDevice.orientation) {
        UIDeviceOrientation.UIDeviceOrientationPortrait,
        UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown,
        -> Orientation.PORTRAIT
        UIDeviceOrientation.UIDeviceOrientationLandscapeLeft,
        UIDeviceOrientation.UIDeviceOrientationLandscapeRight,
        -> Orientation.LANDSCAPE
        else -> Orientation.UNSPECIFIED
    }
