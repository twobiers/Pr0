package com.pr0gramm.app.util.decoders

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import com.pr0gramm.app.util.Logger
import com.squareup.picasso.Downloader

object Decoders {
    fun newImageDecoder(downloader: Downloader): ImageRegionDecoder {
        return adapt(DownloadingRegionDecoder(downloader, FallbackRegionDecoder.chain(listOf(
                AndroidRegionDecoder(Bitmap.Config.RGB_565),
                AndroidRegionDecoder(Bitmap.Config.ARGB_8888),
                SimpleRegionDecoder(Bitmap.Config.RGB_565),
                SimpleRegionDecoder(Bitmap.Config.ARGB_8888)))))
    }

    private fun adapt(dec: Decoder): ImageRegionDecoder {
        val logger = Logger("Decoder")

        return object : ImageRegionDecoder {
            @Volatile
            private var recycled: Boolean = false

            override fun init(context: Context, uri: Uri): Point {
                logger.info { "Decoder.init(context, $uri) called" }
                return dec.takeUnless { recycled }?.init(context, uri) ?: Point(64, 64)
            }

            override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
                logger.info { "Decoder.decodeRegion($sRect, $sampleSize) called" }
                val fallback by lazy { Bitmap.createBitmap(sRect.width(), sRect.height(), Bitmap.Config.RGB_565) }
                if (recycled) {
                    return fallback
                }

                return dec.takeUnless { recycled }?.decodeRegion(sRect, sampleSize) ?: fallback
            }

            override fun isReady(): Boolean {
                return !recycled && dec.isReady()
            }

            override fun recycle() {
                logger.info { "Decoder.recycle() called" }

                recycled = true
                dec.recycle()
            }
        }
    }
}
