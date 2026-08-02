package com.charles.owefolk.data.feedback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.IOException

data class EncodedImage(val base64: String, val extension: String)

object ImageHelper {

    /** Required reusable API: returns a Base64.NO_WRAP representation. */
    fun uriToBase64(context: Context, uri: Uri): String = uriToEncodedImage(context, uri).base64

    fun uriToEncodedImage(context: Context, uri: Uri): EncodedImage {
        require(uri != Uri.EMPTY) { "Empty image URI" }
        val mime = context.contentResolver.getType(uri)?.lowercase()
        val extension = when (mime) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            else -> throw IOException("Choose a PNG, JPEG, or WebP image.")
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
            ?: throw IOException("Unable to open the selected image.")
        if (bytes.isEmpty()) throw IOException("The selected image is empty.")
        if (bytes.size > 5_000_000) throw IOException("The image is larger than 5 MB. Choose a smaller screenshot.")
        return EncodedImage(Base64.encodeToString(bytes, Base64.NO_WRAP), extension)
    }

    /** Decodes a bounded preview without persisting or copying the selected image. */
    fun previewBitmap(context: Context, uri: Uri, maxDimension: Int = 1_200): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}
