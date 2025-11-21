package com.example.demoplayvideo.config

import android.graphics.Matrix
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs

/**
 * Orientation của video
 */
enum class VideoOrientation(val degrees: Int) {
    LANDSCAPE_0(0),
    PORTRAIT_90(90),
    LANDSCAPE_180(180),
    PORTRAITE_270(270);

    companion object {
        fun fromDegrees(degrees: Int): VideoOrientation {
            return values().find { it.degrees == degrees } ?: LANDSCAPE_0
        }
    }

    fun isPortrait(): Boolean = this == PORTRAIT_90 || this == PORTRAITE_270
    fun isLandscape(): Boolean = this == LANDSCAPE_0 || this == LANDSCAPE_180
}

/**
 * Class quản lý orientation và scaling của TextureView
 */
class TextureViewOrientationHandler(private val textureView: TextureView) {

    private var videoWidth = 0
    private var videoHeight = 0
    private var orientation = VideoOrientation.LANDSCAPE_0

    /**
     * Set kích thước video và orientation
     */
    fun setVideoSize(width: Int, height: Int, orientationDegrees: Int) {
        this.videoWidth = width
        this.videoHeight = height
        this.orientation = VideoOrientation.fromDegrees(orientationDegrees)

        updateTextureView()
    }

    /**
     * Chỉ update orientation
     */
    fun setOrientation(orientationDegrees: Int) {
        this.orientation = VideoOrientation.fromDegrees(orientationDegrees)
        updateTextureView()
    }

    /**
     * Update TextureView với orientation và scaling
     */
    fun updateTextureView() {
        if (videoWidth == 0 || videoHeight == 0) return

        val viewWidth = textureView.width
        val viewHeight = textureView.height

        if (viewWidth == 0 || viewHeight == 0) {
            // View chưa layout, đợi sau
            textureView.post { updateTextureView() }
            return
        }

        // Tính toán matrix transformation
        val matrix = calculateTransformMatrix(
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            orientation = orientation
        )

        textureView.setTransform(matrix)

        // Resize view nếu cần
        resizeView(viewWidth, viewHeight)
    }

    /**
     * Tính toán matrix transformation
     */
    private fun calculateTransformMatrix(
        videoWidth: Int,
        videoHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        orientation: VideoOrientation
    ): Matrix {
        val matrix = Matrix()

        // Kích thước video thực tế sau khi rotate
        val rotatedWidth: Int
        val rotatedHeight: Int

        if (orientation.isLandscape()) {
            // 90 hoặc 270 độ - đổi width/height
            rotatedWidth = videoHeight
            rotatedHeight = videoWidth
        } else {
            rotatedWidth = videoWidth
            rotatedHeight = videoHeight
        }

        // Tính scale để fit view
        val scaleX = viewWidth.toFloat() / rotatedWidth
        val scaleY = viewHeight.toFloat() / rotatedHeight
        val scale = maxOf(scaleX, scaleY) // Dùng max để fill view

        // Scale về center
        val scaledWidth = rotatedWidth * scale
        val scaledHeight = rotatedHeight * scale

        // Translate về center
        val translateX = (viewWidth - scaledWidth) / 2f
        val translateY = (viewHeight - scaledHeight) / 2f

        // Apply transformations theo thứ tự
        matrix.postScale(scale, scale)
        matrix.postTranslate(translateX, translateY)

        // Rotate theo orientation (xoay từ center của view)
        if (orientation.degrees != 0) {
            matrix.postRotate(
                orientation.degrees.toFloat(),
                viewWidth / 2f,
                viewHeight / 2f
            )
        }

        return matrix
    }

    /**
     * Resize view để match aspect ratio
     */
    private fun resizeView(containerWidth: Int, containerHeight: Int) {
        val layoutParams = textureView.layoutParams

        // Kích thước video sau khi xoay
        val rotatedWidth = if (orientation.isLandscape()) videoHeight else videoWidth
        val rotatedHeight = if (orientation.isLandscape()) videoWidth else videoHeight

        if (rotatedWidth == 0 || rotatedHeight == 0) return

        val videoAspect = rotatedWidth.toFloat() / rotatedHeight
        val viewAspect = containerWidth.toFloat() / containerHeight

        if (videoAspect > viewAspect) {
            // Video rộng hơn -> match width
            layoutParams.width = containerWidth
            layoutParams.height = (containerWidth / videoAspect).toInt()
        } else {
            // Video cao hơn -> match height
            layoutParams.height = containerHeight
            layoutParams.width = (containerHeight * videoAspect).toInt()
        }

        textureView.layoutParams = layoutParams
    }
}

// ========== PHIÊN BẢN ĐƠN GIẢN HƠN - CENTER CROP ==========

/**
 * Resize TextureView với Center Crop (giống ImageView)
 */
fun TextureView.setVideoSizeAndOrientation(
    videoWidth: Int,
    videoHeight: Int,
    orientationDegrees: Int,
    scaleType: ScaleType = ScaleType.CENTER_CROP
) {
    if (videoWidth == 0 || videoHeight == 0) return

    post {
        val viewWidth = width
        val viewHeight = height
        if (viewWidth == 0 || viewHeight == 0) return@post

        val orientation = VideoOrientation.fromDegrees(orientationDegrees)

        // Kích thước sau khi xoay
        val rotatedWidth = if (orientation.isLandscape()) videoHeight else videoWidth
        val rotatedHeight = if (orientation.isLandscape()) videoWidth else videoHeight

        val matrix = Matrix()

        when (scaleType) {
            ScaleType.CENTER_CROP -> {
                val scaleX = viewWidth.toFloat() / rotatedWidth
                val scaleY = viewHeight.toFloat() / rotatedHeight
                val scale = maxOf(scaleX, scaleY)

                val scaledWidth = rotatedWidth * scale
                val scaledHeight = rotatedHeight * scale

                matrix.postScale(scale, scale)
                matrix.postTranslate(
                    (viewWidth - scaledWidth) / 2f,
                    (viewHeight - scaledHeight) / 2f
                )
            }

            ScaleType.CENTER_INSIDE -> {
                val scaleX = viewWidth.toFloat() / rotatedWidth
                val scaleY = viewHeight.toFloat() / rotatedHeight
                val scale = minOf(scaleX, scaleY)

                val scaledWidth = rotatedWidth * scale
                val scaledHeight = rotatedHeight * scale

                matrix.postScale(scale, scale)
                matrix.postTranslate(
                    (viewWidth - scaledWidth) / 2f,
                    (viewHeight - scaledHeight) / 2f
                )
            }

            ScaleType.FIT_XY -> {
                val scaleX = viewWidth.toFloat() / rotatedWidth
                val scaleY = viewHeight.toFloat() / rotatedHeight
                matrix.postScale(scaleX, scaleY)
            }
        }

        // Rotate
        if (orientationDegrees != 0) {
            matrix.postRotate(
                orientationDegrees.toFloat(),
                viewWidth / 2f,
                viewHeight / 2f
            )
        }

        setTransform(matrix)
    }
}

enum class ScaleType {
    CENTER_CROP,    // Fill view, crop nếu cần
    CENTER_INSIDE,  // Fit bên trong, show full video
    FIT_XY          // Stretch to fill
}