package com.example.quick_pdf_cut.ui.sequence

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.quick_pdf_cut.ui.theme.Quick_PDF_CutTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Item representing a PDF file in the sequence list.
 */
data class PdfSequenceItem(
    val uri: Uri,
    val fileName: String,
)

/**
 * State holder for drag-and-drop reordering inside a [LazyColumn].
 */
class DragDropState(
    val lazyListState: LazyListState,
    val onMove: (fromIndex: Int, toIndex: Int) -> Unit,
) {
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set

    var draggingItemOffset by mutableFloatStateOf(0f)
        private set

    fun onDragStart(offset: Offset) {
        lazyListState.layoutInfo.visibleItemsInfo
            .firstOrNull { item ->
                offset.y.toInt() in (item.offset..(item.offset + item.size))
            }
            ?.let { item ->
                draggingItemIndex = item.index
                draggingItemOffset = 0f
            }
    }

    fun onDrag(dragAmount: Offset) {
        val currentIndex = draggingItemIndex ?: return
        draggingItemOffset += dragAmount.y

        val currentItem = lazyListState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == currentIndex } ?: return

        val startOffset = currentItem.offset + draggingItemOffset
        val endOffset = startOffset + currentItem.size

        val targetItem = lazyListState.layoutInfo.visibleItemsInfo
            .firstOrNull { item ->
                item.index != currentIndex &&
                        if (dragAmount.y > 0) {
                            endOffset > (item.offset + item.size / 2) && currentItem.offset < item.offset
                        } else {
                            startOffset < (item.offset + item.size / 2) && currentItem.offset > item.offset
                        }
            }

        if (targetItem != null) {
            val targetIndex = targetItem.index
            onMove(currentIndex, targetIndex)
            draggingItemIndex = targetIndex
            draggingItemOffset += (currentItem.offset - targetItem.offset)
        }
    }

    fun onDragInterrupted() {
        draggingItemIndex = null
        draggingItemOffset = 0f
    }
}

@Composable
fun rememberDragDropState(
    lazyListState: LazyListState,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
): DragDropState {
    return remember(lazyListState) {
        DragDropState(lazyListState, onMove)
    }
}

/**
 * Screen for reordering via drag-and-drop, previewing, and merging selected PDF files.
 *
 * @param selectedUris Initial list of selected PDF [Uri]s.
 * @param onProceed Callback invoked when merging succeeds, passing the single temporary merged [Uri].
 * @param modifier Custom layout modifier.
 * @param onBack Callback invoked to return to file selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArrangePdfSequenceScreen(
    selectedUris: List<Uri>,
    onProceed: (mergedUri: Uri) -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    var isMerging by remember { mutableStateOf(false) }

    // Initialize list of items with queried filenames
    var pdfList by remember(selectedUris) {
        mutableStateOf(
            selectedUris.map { uri ->
                PdfSequenceItem(
                    uri = uri,
                    fileName = getFileName(context, uri),
                )
            },
        )
    }

    val dragDropState = rememberDragDropState(lazyListState) { fromIndex, toIndex ->
        val list = pdfList.toMutableList()
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        pdfList = list
    }

    fun removeAt(index: Int) {
        if (index in pdfList.indices) {
            val list = pdfList.toMutableList()
            list.removeAt(index)
            pdfList = list
        }
    }

    fun sortAlphabetically() {
        pdfList = pdfList.sortedBy { it.fileName.lowercase() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Arrange PDF Sequence") },
                actions = {
                    TextButton(
                        onClick = { sortAlphabetically() },
                        enabled = pdfList.size > 1 && !isMerging,
                    ) {
                        Text("Sort A-Z")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (pdfList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                text = "No PDF files selected.",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            OutlinedButton(
                                onClick = onBack,
                                enabled = !isMerging,
                            ) {
                                Text("Select PDFs")
                            }
                        }
                    }
                } else {
                    Text(
                        text = "Selected Files (${pdfList.size}) - Long press to drag:",
                        style = MaterialTheme.typography.titleMedium,
                    )

                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .weight(1f)
                            .pointerInput(dragDropState) {
                                if (!isMerging) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset -> dragDropState.onDragStart(offset) },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragDropState.onDrag(dragAmount)
                                        },
                                        onDragEnd = { dragDropState.onDragInterrupted() },
                                        onDragCancel = { dragDropState.onDragInterrupted() },
                                    )
                                }
                            },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(
                            items = pdfList,
                            key = { _, item -> item.uri.toString() },
                        ) { index, item ->
                            val isDragging = dragDropState.draggingItemIndex == index
                            val offset = if (isDragging) dragDropState.draggingItemOffset else 0f

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                                    .graphicsLayer {
                                        translationY = offset
                                        shadowElevation = if (isDragging) 8f else 0f
                                        alpha = if (isDragging) 0.9f else 1f
                                    }
                                    .clickable(enabled = !isMerging) {
                                        previewPdf(context, item.uri)
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isDragging) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    // Sequence Number Badge
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = MaterialTheme.shapes.small,
                                    ) {
                                        Text(
                                            text = (index + 1).toString(),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    // File Name and Tap to Preview Label
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.fileName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "Tap to preview",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    // Actions: Remove Button & Drag Handle Indicator
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        // Remove Button
                                        Button(
                                            onClick = { removeAt(index) },
                                            enabled = !isMerging,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error,
                                                contentColor = MaterialTheme.colorScheme.onError,
                                            ),
                                        ) {
                                            Text("✕")
                                        }

                                        // Drag Handle Symbol
                                        Text(
                                            text = "≡",
                                            style = MaterialTheme.typography.titleLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Bottom Action Button
                    Button(
                        onClick = {
                            isMerging = true
                            scope.launch {
                                val orderedUris = pdfList.map { it.uri }
                                val resultUri = mergePdfs(context, orderedUris)
                                isMerging = false

                                if (resultUri != null) {
                                    onProceed(resultUri)
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Failed to merge PDF files.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        },
                        enabled = pdfList.isNotEmpty() && !isMerging,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = "Merge & Open")
                    }
                }
            }

            // Full-screen Loading Overlay during PDF merge
            if (isMerging) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center,
                ) {
                    Card(
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Merging PDF files...",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Merges a list of PDF URIs into a single temporary PDF file on Dispatchers.IO.
 *
 * @param context Application context.
 * @param uris Ordered list of PDF URIs to merge.
 * @return [Uri] pointing to the temporary merged file in cacheDir, or null on failure.
 */
suspend fun mergePdfs(
    context: Context,
    uris: List<Uri>,
): Uri? = withContext(Dispatchers.IO) {
    if (uris.isEmpty()) return@withContext null

    runCatching {
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context.applicationContext)
        }

        // 1. Destination temporary file in cache directory
        val tempFile = File(context.cacheDir, "temp_merged_doc.pdf")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        val merger = PDFMergerUtility()
        val streams = mutableListOf<InputStream>()

        try {
            // 2. Open InputStreams for each source URI
            for (uri in uris) {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Unable to open input stream for $uri")
                streams.add(inputStream)
                merger.addSource(inputStream)
            }

            // 3. Write merged document to output stream
            FileOutputStream(tempFile).use { outputStream ->
                merger.destinationStream = outputStream
                merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly())
            }

            Uri.fromFile(tempFile)
        } finally {
            // 4. Safely close all input streams
            for (stream in streams) {
                try {
                    stream.close()
                } catch (e: Exception) {
                    Log.e("MergePdfs", "Error closing input stream", e)
                }
            }
        }
    }.getOrElse { e ->
        Log.e("MergePdfs", "Failed to merge PDF files", e)
        null
    }
}

/**
 * Launches an external viewer intent for the specified PDF [Uri].
 */
fun previewPdf(context: Context, uri: Uri) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e("ArrangePdfSequence", "Error opening external PDF viewer for $uri", e)
        Toast.makeText(context, "No app found to open PDF.", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Queries the display filename for a given Content [Uri].
 */
fun getFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ArrangePdfSequence", "Error querying display name for $uri", e)
        }
    }
    if (result == null) {
        val path = uri.path
        if (path != null) {
            val cut = path.lastIndexOf('/')
            result = if (cut != -1) path.substring(cut + 1) else path
        }
    }
    return result ?: "document.pdf"
}

@Preview(showBackground = true)
@Composable
fun ArrangePdfSequenceScreenPreview() {
    Quick_PDF_CutTheme {
        ArrangePdfSequenceScreen(
            selectedUris = listOf(
                "content://com.android.providers.downloads.documents/document/1".toUri(),
                "content://com.android.providers.downloads.documents/document/2".toUri(),
            ),
            onProceed = {},
        )
    }
}
