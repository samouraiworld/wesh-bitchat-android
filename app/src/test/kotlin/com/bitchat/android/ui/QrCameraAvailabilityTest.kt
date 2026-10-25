package com.bitchat.android.ui

import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class QrCameraAvailabilityTest {
    @Test
    fun `a camera-less device cannot scan even when permission could be granted`() {
        val packageManager = mock<PackageManager>()
        whenever(packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)).thenReturn(false)

        assertFalse(hasQrCamera(packageManager))
        verify(packageManager).hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    @Test
    fun `a device with any camera can enter the permission flow`() {
        val packageManager = mock<PackageManager>()
        whenever(packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)).thenReturn(true)

        assertTrue(hasQrCamera(packageManager))
    }

    @Test
    fun `rear camera is preferred when both sensors exist`() {
        assertSame(CameraSelector.DEFAULT_BACK_CAMERA, qrCameraSelector(hasBackCamera = true, hasFrontCamera = true))
        assertSame(CameraSelector.DEFAULT_BACK_CAMERA, qrCameraSelector(hasBackCamera = true, hasFrontCamera = false))
    }

    @Test
    fun `a front-only tablet uses its front camera`() {
        assertSame(CameraSelector.DEFAULT_FRONT_CAMERA, qrCameraSelector(hasBackCamera = false, hasFrontCamera = true))
    }

    @Test
    fun `no sensor cannot select a camera`() {
        assertNull(qrCameraSelector(hasBackCamera = false, hasFrontCamera = false))
    }
}
