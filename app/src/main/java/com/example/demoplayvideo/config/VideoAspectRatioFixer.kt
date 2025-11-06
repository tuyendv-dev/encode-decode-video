package com.example.demoplayvideo.config

import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import kotlin.math.min

/**
 * Fix video aspect ratio và rotation issues
 */
object VideoAspectRatioFixer {
    private const val TAG = "VideoAspectRatio"

    /**
     * Calculate correct display size với rotation
     *
     * @param videoWidth Video width from codec
     * @param videoHeight Video height from codec
     * @param rotation Video rotation (0, 90, 180, 270)
     * @param viewWidth Container view width
     * @param viewHeight Container view height
     * @return Pair<width, height> để set cho SurfaceView/TextureView
     */
    fun calculateDisplaySize(
        videoWidth: Int,
        videoHeight: Int,
        rotation: Int,
        viewWidth: Int,
        viewHeight: Int
    ): Pair<Int, Int> {

        // Nếu rotation là 90 hoặc 270, swap width/height
        val (rotatedWidth, rotatedHeight) = if (rotation == 90 || rotation == 270) {
            videoHeight to videoWidth
        } else {
            videoWidth to videoHeight
        }

        Log.d(TAG, "Calculating display size:")
        Log.d(TAG, "  Video: ${videoWidth}x${videoHeight}")
        Log.d(TAG, "  Rotation: $rotation")
        Log.d(TAG, "  Rotated: ${rotatedWidth}x${rotatedHeight}")
        Log.d(TAG, "  View: ${viewWidth}x${viewHeight}")

        // Tính aspect ratio
        val videoAspect = rotatedWidth.toFloat() / rotatedHeight.toFloat()
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

        val (displayWidth, displayHeight) = if (viewAspect > videoAspect) {
            // View rộng hơn -> fit height
            val width = (viewHeight * videoAspect).toInt()
            width to viewHeight
        } else {
            // View cao hơn -> fit width
            val height = (viewWidth / videoAspect).toInt()
            viewWidth to height
        }

        Log.d(TAG, "  Display: ${displayWidth}x${displayHeight}")

        return displayWidth to displayHeight
    }

    /**
     * Apply correct size to SurfaceView
     */
    fun applySizeToSurfaceView(
        surfaceView: SurfaceView,
        viewWidthMax: Int,
        viewHeightMax: Int,
        videoWidth: Int,
        videoHeight: Int,
        rotation: Int
    ) {
        val (displayWidth, displayHeight) = calculateDisplaySize(
            videoWidth,
            videoHeight,
            rotation,
            viewWidthMax,
            viewHeightMax
        )

        // Set layout params
        val layoutParams = surfaceView.layoutParams
        layoutParams.width = displayWidth
        layoutParams.height = displayHeight
        surfaceView.layoutParams = layoutParams

        Log.d(TAG, "✓ SurfaceView size applied: ${displayWidth}x${displayHeight}")
    }

    /**
     * Apply correct size to TextureView với rotation
     */
    fun applyToTextureView(
        textureView: TextureView,
        videoWidth: Int,
        videoHeight: Int,
        rotation: Int
    ) {
        val viewWidth = textureView.width
        val viewHeight = textureView.height

        if (viewWidth == 0 || viewHeight == 0) {
            Log.w(TAG, "View size not ready, will apply later")
            return
        }

        val (displayWidth, displayHeight) = calculateDisplaySize(
            videoWidth,
            videoHeight,
            rotation,
            viewWidth,
            viewHeight
        )

        // Set layout params
        val layoutParams = textureView.layoutParams
        layoutParams.width = displayWidth
        layoutParams.height = displayHeight
        textureView.layoutParams = layoutParams

        // Apply rotation nếu cần
        textureView.rotation = rotation.toFloat()

        Log.d(TAG, "✓ TextureView size applied: ${displayWidth}x${displayHeight}, rotation: $rotation")
    }

    /**
     * Calculate scale type FIT_CENTER
     */
    fun calculateFitCenterTransform(
        videoWidth: Int,
        videoHeight: Int,
        rotation: Int,
        viewWidth: Int,
        viewHeight: Int
    ): VideoTransform {

        val (rotatedWidth, rotatedHeight) = if (rotation == 90 || rotation == 270) {
            videoHeight to videoWidth
        } else {
            videoWidth to videoHeight
        }

        val videoAspect = rotatedWidth.toFloat() / rotatedHeight.toFloat()
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

        val scale = if (viewAspect > videoAspect) {
            // Fit height
            viewHeight.toFloat() / rotatedHeight.toFloat()
        } else {
            // Fit width
            viewWidth.toFloat() / rotatedWidth.toFloat()
        }

        val scaledWidth = (rotatedWidth * scale).toInt()
        val scaledHeight = (rotatedHeight * scale).toInt()

        val translateX = (viewWidth - scaledWidth) / 2f
        val translateY = (viewHeight - scaledHeight) / 2f

        return VideoTransform(
            scaleX = scale,
            scaleY = scale,
            translateX = translateX,
            translateY = translateY,
            rotation = rotation.toFloat()
        )
    }

    data class VideoTransform(
        val scaleX: Float,
        val scaleY: Float,
        val translateX: Float,
        val translateY: Float,
        val rotation: Float
    )
}

/**
 * Custom SurfaceView với auto aspect ratio
 */
class AspectRatioSurfaceView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {

    private var videoWidth = 0
    private var videoHeight = 0
    private var videoRotation = 0

    /**
     * Set video size và rotation
     */
    fun setVideoSize(width: Int, height: Int, rotation: Int = 0) {
        if (videoWidth != width || videoHeight != height || videoRotation != rotation) {
            videoWidth = width
            videoHeight = height
            videoRotation = rotation

            Log.d("AspectRatioSurface", "Video size set: ${width}x${height}, rotation: $rotation")
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = View.getDefaultSize(videoWidth, widthMeasureSpec)
        var height = View.getDefaultSize(videoHeight, heightMeasureSpec)

        if (videoWidth > 0 && videoHeight > 0) {
            // Swap dimensions nếu rotation 90 hoặc 270
            val (rotatedWidth, rotatedHeight) = if (videoRotation == 90 || videoRotation == 270) {
                videoHeight to videoWidth
            } else {
                videoWidth to videoHeight
            }

            val widthSpecSize = View.MeasureSpec.getSize(widthMeasureSpec)
            val heightSpecSize = View.MeasureSpec.getSize(heightMeasureSpec)

            val videoAspect = rotatedWidth.toFloat() / rotatedHeight.toFloat()
            val viewAspect = widthSpecSize.toFloat() / heightSpecSize.toFloat()

            if (viewAspect > videoAspect) {
                // View rộng hơn, fit theo height
                height = heightSpecSize
                width = (height * videoAspect).toInt()
            } else {
                // View cao hơn, fit theo width
                width = widthSpecSize
                height = (width / videoAspect).toInt()
            }

            Log.d("AspectRatioSurface", "Measured: ${width}x${height}")
        }

        setMeasuredDimension(width, height)
    }
}

/**
 * Custom TextureView với auto aspect ratio
 */
class AspectRatioTextureView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr) {

    private var videoWidth = 0
    private var videoHeight = 0
    private var videoRotation = 0

    fun setVideoSize(width: Int, height: Int, rotation: Int = 0) {
        if (videoWidth != width || videoHeight != height || videoRotation != rotation) {
            videoWidth = width
            videoHeight = height
            videoRotation = rotation

            Log.d("AspectRatioTexture", "Video size set: ${width}x${height}, rotation: $rotation")
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = View.getDefaultSize(videoWidth, widthMeasureSpec)
        var height = View.getDefaultSize(videoHeight, heightMeasureSpec)

        if (videoWidth > 0 && videoHeight > 0) {
            val (rotatedWidth, rotatedHeight) = if (videoRotation == 90 || videoRotation == 270) {
                videoHeight to videoWidth
            } else {
                videoWidth to videoHeight
            }

            val widthSpecSize = View.MeasureSpec.getSize(widthMeasureSpec)
            val heightSpecSize = View.MeasureSpec.getSize(heightMeasureSpec)

            val videoAspect = rotatedWidth.toFloat() / rotatedHeight.toFloat()
            val viewAspect = widthSpecSize.toFloat() / heightSpecSize.toFloat()

            if (viewAspect > videoAspect) {
                height = heightSpecSize
                width = (height * videoAspect).toInt()
            } else {
                width = widthSpecSize
                height = (width / videoAspect).toInt()
            }
        }

        setMeasuredDimension(width, height)
    }
}

/**
 * Extension functions
 */
//fun SurfaceView.setVideoAspectRatio(
//    videoWidth: Int,
//    videoHeight: Int,
//    rotation: Int = 0
//) {
//    VideoAspectRatioFixer.applySizeToSurfaceView(this, videoWidth, videoHeight, rotation)
//}
//
//fun TextureView.setVideoAspectRatio(
//    videoWidth: Int,
//    videoHeight: Int,
//    rotation: Int = 0
//) {
//    VideoAspectRatioFixer.applyToTextureView(this, videoWidth, videoHeight, rotation)
//}