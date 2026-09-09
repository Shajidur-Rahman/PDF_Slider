package com.example.quick_pdf_cut.worker

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Foreground CoroutineWorker executing zero-leak, flat-memory PDF processing in the background.
 */
class PdfProcessingWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    private var destinationUriString: String? = null

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val originalUriString = inputData.getString(KEY_ORIGINAL_URI)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Missing original PDF URI."))
        destinationUriString = inputData.getString(KEY_DESTINATION_URI)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Missing destination PDF URI."))

        val pagesToDeleteArray = inputData.getIntArray(KEY_PAGES_TO_DELETE) ?: intArrayOf()
        val invertColors = inputData.getBoolean(KEY_INVERT_COLORS, false)
        val make3Up = inputData.getBoolean(KEY_MAKE_3UP, false)

        val originalUri = originalUriString.toUri()
        val destinationUri = destinationUriString!!.toUri()
        val pagesToDeleteSet = pagesToDeleteArray.toSet()

        // Promote to Foreground Service with notification
        setForeground(createForegroundInfo(0, 100, "Starting PDF Export..."))

        try {
            if (!PDFBoxResourceLoader.isReady()) {
                PDFBoxResourceLoader.init(applicationContext)
            }

            if (make3Up) {
                // =========================================================================
                // 3-UP FLAT-MEMORY PIPELINE (3 Slides per A4 Page)
                // =========================================================================
                val inputStream = applicationContext.contentResolver.openInputStream(originalUri)
                    ?: throw IllegalStateException("Unable to open input stream for source PDF.")

                var sourceDoc: PDDocument? = null
                var targetDoc: PDDocument? = null

                try {
                    sourceDoc = PDDocument.load(inputStream)
                    val totalPages = sourceDoc.numberOfPages

                    val remainingPageIndices = (0 until totalPages).filterNot { it in pagesToDeleteSet }
                    if (remainingPageIndices.isEmpty()) {
                        throw IllegalStateException("Cannot export an empty PDF document. All pages were marked for deletion.")
                    }

                    targetDoc = PDDocument()
                    val layerUtility = LayerUtility(targetDoc)

                    // A4 Dimensions & Asymmetric Margins (45pt left for binding, 10pt right, 0pt vertical)
                    val a4Width = PDRectangle.A4.width
                    val a4Height = PDRectangle.A4.height
                    val leftMargin = 45f
                    val rightMargin = 10f

                    val maxWidth = a4Width - leftMargin - rightMargin
                    val maxHeight = a4Height / 3f

                    val pageChunks = remainingPageIndices.chunked(3)
                    val totalChunks = pageChunks.size

                    if (!invertColors) {
                        // Option A: Vector 3-Up using PdfBox FormXObjects
                        for ((chunkIndex, chunk) in pageChunks.withIndex()) {
                            updateProgress(chunkIndex + 1, totalChunks, "Processing chunk ${chunkIndex + 1} of $totalChunks...")

                            val a4Page = PDPage(PDRectangle.A4)
                            targetDoc.addPage(a4Page)

                            val contentStream = PDPageContentStream(targetDoc, a4Page)

                            for ((slotIndex, origPageIndex) in chunk.withIndex()) {
                                val formXObject = layerUtility.importPageAsForm(sourceDoc, origPageIndex)
                                val bbox = formXObject.bBox ?: sourceDoc.getPage(origPageIndex).mediaBox
                                val origWidth = bbox.width
                                val origHeight = bbox.height

                                val scaleX = maxWidth / origWidth
                                val scaleY = maxHeight / origHeight
                                val scale = minOf(scaleX, scaleY)

                                val scaledWidth = origWidth * scale
                                val scaledHeight = origHeight * scale

                                val xOffset = leftMargin + ((maxWidth - scaledWidth) / 2f)
                                val slotBottom = a4Height - ((slotIndex + 1) * maxHeight)
                                val yOffset = slotBottom + ((maxHeight - scaledHeight) / 2f)

                                contentStream.saveGraphicsState()
                                val matrix = Matrix.getTranslateInstance(xOffset, yOffset)
                                matrix.concatenate(Matrix.getScaleInstance(scale, scale))
                                contentStream.transform(matrix)
                                contentStream.drawForm(formXObject)
                                contentStream.restoreGraphicsState()
                            }

                            contentStream.close()
                        }
                    } else {
                        // Option B: Rasterized 3-Up with Color Inversion
                        val pfd = applicationContext.contentResolver.openFileDescriptor(originalUri, "r")
                            ?: throw IllegalStateException("Unable to open file descriptor for rasterizing PDF.")

                        var renderer: PdfRenderer? = null
                        try {
                            renderer = PdfRenderer(pfd)

                            val colorMatrix = ColorMatrix(
                                floatArrayOf(
                                    -1f, 0f, 0f, 0f, 255f,
                                    0f, -1f, 0f, 0f, 255f,
                                    0f, 0f, -1f, 0f, 255f,
                                    0f, 0f, 0f, 1f, 0f,
                                ),
                            )
                            val paint = Paint().apply {
                                colorFilter = ColorMatrixColorFilter(colorMatrix)
                            }

                            val rasterScaleFactor = 2.0f

                            for ((chunkIndex, chunk) in pageChunks.withIndex()) {
                                updateProgress(chunkIndex + 1, totalChunks, "Inverting chunk ${chunkIndex + 1} of $totalChunks...")

                                val a4Page = PDPage(PDRectangle.A4)
                                targetDoc.addPage(a4Page)

                                val contentStream = PDPageContentStream(targetDoc, a4Page)

                                // SEQUENTIAL DISPOSAL LOOP: Process & recycle 1 slide at a time
                                for ((slotIndex, origPageIndex) in chunk.withIndex()) {
                                    var pdfPage: PdfRenderer.Page? = null
                                    var bitmap: Bitmap? = null
                                    var invertedBitmap: Bitmap? = null

                                    try {
                                        pdfPage = renderer.openPage(origPageIndex)
                                        val origWidth = pdfPage.width.toFloat()
                                        val origHeight = pdfPage.height.toFloat()

                                        val scaleX = maxWidth / origWidth
                                        val scaleY = maxHeight / origHeight
                                        val scale = minOf(scaleX, scaleY)

                                        val scaledWidth = origWidth * scale
                                        val scaledHeight = origHeight * scale

                                        val xOffset = leftMargin + ((maxWidth - scaledWidth) / 2f)
                                        val slotBottom = a4Height - ((slotIndex + 1) * maxHeight)
                                        val yOffset = slotBottom + ((maxHeight - scaledHeight) / 2f)

                                        val bitmapWidth = (origWidth * rasterScaleFactor).toInt().coerceAtLeast(1)
                                        val bitmapHeight = (origHeight * rasterScaleFactor).toInt().coerceAtLeast(1)

                                        bitmap = createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                                        val canvas = Canvas(bitmap)
                                        canvas.drawColor(Color.WHITE)
                                        pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                                        pdfPage.close()
                                        pdfPage = null

                                        invertedBitmap = createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                                        val invertCanvas = Canvas(invertedBitmap)
                                        invertCanvas.drawBitmap(bitmap, 0f, 0f, paint)

                                        bitmap.recycle()
                                        bitmap = null

                                        // Embed lossless compressed image for razor-sharp text quality
                                        val pdImage = LosslessFactory.createFromImage(targetDoc, invertedBitmap)
                                        contentStream.drawImage(pdImage, xOffset, yOffset, scaledWidth, scaledHeight)

                                        invertedBitmap.recycle()
                                        invertedBitmap = null
                                    } finally {
                                        pdfPage?.close()
                                        bitmap?.let { if (!it.isRecycled) it.recycle() }
                                        invertedBitmap?.let { if (!it.isRecycled) it.recycle() }
                                    }
                                }

                                contentStream.close()
                            }
                        } finally {
                            try {
                                renderer?.close()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error closing PdfRenderer", e)
                            }
                            try {
                                pfd.close()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error closing ParcelFileDescriptor", e)
                            }
                        }
                    }

                    val outputStream = applicationContext.contentResolver.openOutputStream(destinationUri, "w")
                        ?: throw IllegalStateException("Unable to open output stream for destination PDF.")

                    outputStream.use { out ->
                        targetDoc.save(out)
                    }
                } finally {
                    try {
                        targetDoc?.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing target PDDocument", e)
                    }
                    try {
                        sourceDoc?.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing source PDDocument", e)
                    }
                    try {
                        inputStream.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing InputStream", e)
                    }
                }
            } else if (!invertColors) {
                // =========================================================================
                // STANDARD PAGE DELETION WITHOUT 3-UP OR COLOR INVERSION
                // =========================================================================
                val descendingPagesToDelete = pagesToDeleteSet.sortedDescending()
                val inputStream = applicationContext.contentResolver.openInputStream(originalUri)
                    ?: throw IllegalStateException("Unable to open input stream for source PDF.")

                var document: PDDocument? = null
                try {
                    document = PDDocument.load(inputStream)
                    val totalPages = document.numberOfPages

                    if (descendingPagesToDelete.size >= totalPages) {
                        throw IllegalStateException("Cannot delete all pages. At least one page must remain.")
                    }

                    for ((deletedCount, pageIndex) in descendingPagesToDelete.withIndex()) {
                        updateProgress(deletedCount + 1, descendingPagesToDelete.size, "Removing page $pageIndex...")
                        if (pageIndex in (0 until document.numberOfPages)) {
                            document.removePage(pageIndex)
                        }
                    }

                    val outputStream = applicationContext.contentResolver.openOutputStream(destinationUri, "w")
                        ?: throw IllegalStateException("Unable to open output stream for destination PDF.")

                    outputStream.use { out ->
                        document.save(out)
                    }
                } finally {
                    try {
                        document?.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing PDDocument", e)
                    }
                    try {
                        inputStream.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing InputStream", e)
                    }
                }
            } else {
                // =========================================================================
                // STANDARD COLOR INVERSION STREAMING PIPELINE VIA PDFBOX
                // =========================================================================
                val pfd = applicationContext.contentResolver.openFileDescriptor(originalUri, "r")
                    ?: throw IllegalStateException("Unable to open file descriptor for source PDF.")

                var renderer: PdfRenderer? = null
                var targetDoc: PDDocument? = null

                try {
                    renderer = PdfRenderer(pfd)
                    val totalPages = renderer.pageCount

                    val remainingPagesCount = totalPages - pagesToDeleteSet.count { it in (0 until totalPages) }
                    if (remainingPagesCount <= 0) {
                        throw IllegalStateException("Cannot delete all pages. At least one page must remain.")
                    }

                    targetDoc = PDDocument()

                    val colorMatrix = ColorMatrix(
                        floatArrayOf(
                            -1f, 0f, 0f, 0f, 255f,
                            0f, -1f, 0f, 0f, 255f,
                            0f, 0f, -1f, 0f, 255f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    )
                    val paint = Paint().apply {
                        colorFilter = ColorMatrixColorFilter(colorMatrix)
                    }

                    val rasterScaleFactor = 2.0f
                    var processedCount = 0

                    for (i in 0 until totalPages) {
                        if (i in pagesToDeleteSet) continue

                        processedCount++
                        updateProgress(processedCount, remainingPagesCount, "Inverting page $processedCount of $remainingPagesCount...")

                        var page: PdfRenderer.Page? = null
                        var bitmap: Bitmap? = null
                        var invertedBitmap: Bitmap? = null

                        try {
                            page = renderer.openPage(i)
                            val width = page.width.toFloat()
                            val height = page.height.toFloat()

                            val bitmapWidth = (width * rasterScaleFactor).toInt().coerceAtLeast(1)
                            val bitmapHeight = (height * rasterScaleFactor).toInt().coerceAtLeast(1)

                            bitmap = createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bitmap)
                            canvas.drawColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                            page.close()
                            page = null

                            invertedBitmap = createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                            val invertCanvas = Canvas(invertedBitmap)
                            invertCanvas.drawBitmap(bitmap, 0f, 0f, paint)

                            bitmap.recycle()
                            bitmap = null

                            // Create PDPage matching source page dimensions
                            val pdPage = PDPage(PDRectangle(width, height))
                            targetDoc.addPage(pdPage)

                            // Embed lossless compressed image for razor-sharp text quality
                            val pdImage = LosslessFactory.createFromImage(targetDoc, invertedBitmap)
                            val contentStream = PDPageContentStream(targetDoc, pdPage)
                            contentStream.drawImage(pdImage, 0f, 0f, width, height)
                            contentStream.close()

                            invertedBitmap.recycle()
                            invertedBitmap = null
                        } finally {
                            try {
                                page?.close()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error closing page $i", e)
                            }
                            bitmap?.let { if (!it.isRecycled) it.recycle() }
                            invertedBitmap?.let { if (!it.isRecycled) it.recycle() }
                        }
                    }

                    val outputStream = applicationContext.contentResolver.openOutputStream(destinationUri, "w")
                        ?: throw IllegalStateException("Unable to open output stream for destination PDF.")

                    outputStream.use { out ->
                        targetDoc.save(out)
                    }
                } finally {
                    try {
                        targetDoc?.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing target PDDocument", e)
                    }
                    try {
                        renderer?.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing PdfRenderer", e)
                    }
                    try {
                        pfd.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing ParcelFileDescriptor", e)
                    }
                }
            }

            // Post completion notification
            showCompletionNotification("PDF Export Complete!")

            Result.success(workDataOf(KEY_RESULT_MESSAGE to "PDF export completed successfully."))
        } catch (e: Exception) {
            Log.e(TAG, "Error in PdfProcessingWorker", e)
            val errMessage = e.localizedMessage ?: "PDF processing failed."
            showCompletionNotification("PDF Export Failed: $errMessage", isError = true)
            Result.failure(workDataOf(KEY_ERROR_MESSAGE to errMessage))
        }
    }

    private suspend fun updateProgress(current: Int, total: Int, message: String) {
        setProgress(
            workDataOf(
                KEY_PROGRESS_CURRENT to current,
                KEY_PROGRESS_TOTAL to total,
                KEY_PROGRESS_MESSAGE to message,
            ),
        )
        setForeground(createForegroundInfo(current, total, message))
    }

    private fun createForegroundInfo(
        current: Int,
        total: Int,
        message: String,
    ): ForegroundInfo {
        val notification = createNotification(current, total, message, isComplete = false)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ForegroundInfo(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                ForegroundInfo(NOTIFICATION_ID, notification)
            }
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(
        current: Int,
        total: Int,
        message: String,
        isComplete: Boolean,
        isError: Boolean = false,
    ): Notification {
        createNotificationChannel()

        val titleText = if (isComplete) {
            if (isError) "PDF Export Failed" else "PDF Export Complete"
        } else {
            "Exporting PDF"
        }

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_menu_save)
            .setContentTitle(titleText)
            .setContentText(message)
            .setOngoing(!isComplete)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if ((!isComplete) && (total > 0)) {
            builder.setProgress(total, current, false)
        } else if (isComplete) {
            builder.setProgress(0, 0, false)
            if (!isError) {
                destinationUriString?.let { uriStr ->
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uriStr.toUri(), "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        applicationContext,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    builder.setContentIntent(pendingIntent)
                    builder.setAutoCancel(true)
                }
            }
        }

        return builder.build()
    }

    private fun showCompletionNotification(message: String, isError: Boolean = false) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = createNotification(0, 0, message, isComplete = true, isError = isError)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PDF Export Notifications",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows live progress for background PDF processing"
            }
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "PdfProcessingWorker"
        private const val CHANNEL_ID = "pdf_export_channel"
        private const val NOTIFICATION_ID = 1001

        const val KEY_ORIGINAL_URI = "key_original_uri"
        const val KEY_DESTINATION_URI = "key_destination_uri"
        const val KEY_PAGES_TO_DELETE = "key_pages_to_delete"
        const val KEY_INVERT_COLORS = "key_invert_colors"
        const val KEY_MAKE_3UP = "key_make_3up"

        const val KEY_PROGRESS_CURRENT = "key_progress_current"
        const val KEY_PROGRESS_TOTAL = "key_progress_total"
        const val KEY_PROGRESS_MESSAGE = "key_progress_message"

        const val KEY_RESULT_MESSAGE = "key_result_message"
        const val KEY_ERROR_MESSAGE = "key_error_message"
    }
}
