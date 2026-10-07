package com.example.mycar.capture

import android.graphics.Bitmap
import android.graphics.Point
import java.nio.ByteBuffer

/**
 * Single-slot hand-off between the screen-capture thread and the Android Auto render thread.
 *
 * Capture and rendering run on different threads and share one [Bitmap], so both sides do
 * their work while holding [lock]. One frame of contention is invisible at 30 fps and it
 * keeps the pipeline completely allocation-free, which matters because a full-screen
 * ARGB_8888 frame is several megabytes.
 */
object FrameStore {

    private val lock = Any()

    private var frame: Bitmap? = null

    /** The usable picture inside [frame]. Its width can be smaller than the bitmap's. */
    private var pictureWidth = 0
    private var pictureHeight = 0

    /**
     * Copies one captured screen buffer over the current frame.
     *
     * [rowStride] is the real stride of the capture buffer and is usually larger than
     * `width * pixelStride`. The bitmap is therefore allocated `rowStride` wide and the
     * extra columns are cropped out at draw time by [withFrame]'s reported width.
     * Ignoring this is the classic cause of a diagonally skewed mirror image.
     */
    fun update(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int) {
        val strideWidth = rowStride / pixelStride
        val byteCount = strideWidth * pixelStride * height
        if (buffer.capacity() < byteCount) return

        synchronized(lock) {
            val bitmap = frame
                ?.takeIf { it.width == strideWidth && it.height == height }
                ?: Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888)
                    .also { frame = it }

            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            pictureWidth = width
            pictureHeight = height
        }
    }

    /**
     * Runs [block] on the most recent frame while holding the lock, so the capture thread
     * cannot overwrite the pixels mid-draw. [width]/[height] are the cropped picture size;
     * `bitmap.width` may be larger.
     */
    fun withFrame(block: (bitmap: Bitmap, width: Int, height: Int) -> Unit) {
        synchronized(lock) {
            val bitmap = frame ?: return
            block(bitmap, pictureWidth, pictureHeight)
        }
    }

    /**
     * Size of the cropped picture in the current frame, or null when there is none. Used to map
     * car touch coordinates back to phone pixels.
     */
    fun pictureSize(): Point? = synchronized(lock) {
        val bitmap = frame
        if (bitmap == null || pictureWidth <= 0 || pictureHeight <= 0) {
            null
        } else {
            Point(pictureWidth, pictureHeight)
        }
    }

    /** Drops and recycles the current frame. Safe to call when capture is not running. */
    fun clear() {
        synchronized(lock) {
            frame?.recycle()
            frame = null
            pictureWidth = 0
            pictureHeight = 0
        }
    }
}
