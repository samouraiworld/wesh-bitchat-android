package com.bitchat.android.ui

import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector

/** Camera permission cannot enable a sensor that the device does not have. */
internal fun hasQrCamera(packageManager: PackageManager): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/** Prefer the rear sensor for a face-to-face exchange, but support front-only tablets. */
internal fun qrCameraSelector(hasBackCamera: Boolean, hasFrontCamera: Boolean): CameraSelector? = when {
    hasBackCamera -> CameraSelector.DEFAULT_BACK_CAMERA
    hasFrontCamera -> CameraSelector.DEFAULT_FRONT_CAMERA
    else -> null
}
