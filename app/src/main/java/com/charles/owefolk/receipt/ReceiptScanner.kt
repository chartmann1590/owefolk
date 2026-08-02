package com.charles.owefolk.receipt

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File

object ReceiptScanner {
    suspend fun scan(context: Context, uri: Uri): ReceiptSuggestion {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            ReceiptParser.parse(recognizer.process(image).await().text)
        } finally {
            recognizer.close()
        }
    }

    fun newCameraUri(context: Context): Uri {
        val directory = File(context.cacheDir, "receipts").apply { mkdirs() }
        val image = File.createTempFile("receipt_", ".jpg", directory)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", image)
    }
}
