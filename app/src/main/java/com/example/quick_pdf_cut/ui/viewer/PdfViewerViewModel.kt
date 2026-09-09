package com.example.quick_pdf_cut.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI State for [PdfViewerScreen].
 */
data class PdfViewerUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val currentPageIndex: Int = 0,
    val totalPages: Int = 0,
    val currentPageBitmap: Bitmap? = null,
    val markedForDeletionPages: Set<Int> = emptySet(),
) {
    /**
     * Sorted list of 0-based page indices marked for deletion.
     */
    val pagesToDelete: List<Int>
        get() = markedForDeletionPages.sorted()
}

/**
 * ViewModel managing PDF page rendering via native [PdfRenderer].
 * Maintains state for page navigation and zero-based deletion page indices.
 * Ensures memory safety by recycling Bitmaps and closing [PdfRenderer.Page] immediately after rendering.
 */
class PdfViewerViewModel(
    application: Application,
    private val uri: Uri,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PdfViewerUiState())
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    init {
        loadPdf()
    }

    private fun loadPdf() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>().applicationContext
                    parcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    val pfd = parcelFileDescriptor
                        ?: throw IllegalStateException("Could not open file descriptor for URI: $uri")

                    val renderer = PdfRenderer(pfd)
                    pdfRenderer = renderer

                    val pageCount = renderer.pageCount
                    if (pageCount == 0) {
                        throw IllegalStateException("The selected PDF document contains no pages.")
                    }

                    _uiState.update {
                        it.copy(
                            totalPages = pageCount,
                            currentPageIndex = 0,
                        )
                    }
                    renderPageInternal(0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading PDF", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "Failed to load PDF document.",
                    )
                }
            }
        }
    }

    /**
     * Renders a specific page index in a background thread safely.
     */
    private fun renderPageInternal(pageIndex: Int) {
        val renderer = pdfRenderer ?: return
        if (pageIndex !in (0..<renderer.pageCount)) return

        var page: PdfRenderer.Page? = null
        try {
            // Open the requested page
            page = renderer.openPage(pageIndex)

            // Render at crisp resolution while maintaining page aspect ratio
            val scaleFactor = 2f
            val width = (page.width * scaleFactor).toInt().coerceAtLeast(1)
            val height = (page.height * scaleFactor).toInt().coerceAtLeast(1)

            val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            // CRITICAL: Close the page immediately after rendering
            page.close()
            page = null

            val previousBitmap = _uiState.value.currentPageBitmap

            _uiState.update {
                it.copy(
                    isLoading = false,
                    currentPageIndex = pageIndex,
                    currentPageBitmap = bitmap,
                )
            }

            // Recycle previous bitmap safely to prevent OutOfMemoryError
            if ((previousBitmap != null) && (!previousBitmap.isRecycled) && (previousBitmap != bitmap)) {
                previousBitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering page $pageIndex", e)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = e.localizedMessage ?: "Failed to render page $pageIndex.",
                )
            }
        } finally {
            // Ensure page is closed even if an exception occurs
            try {
                page?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing page", e)
            }
        }
    }

    fun nextPage() {
        val currentState = _uiState.value
        if (currentState.currentPageIndex < currentState.totalPages - 1) {
            val nextIndex = currentState.currentPageIndex + 1
            viewModelScope.launch(Dispatchers.IO) {
                _uiState.update { it.copy(isLoading = true) }
                renderPageInternal(nextIndex)
            }
        }
    }

    fun previousPage() {
        val currentState = _uiState.value
        if (currentState.currentPageIndex > 0) {
            val prevIndex = currentState.currentPageIndex - 1
            viewModelScope.launch(Dispatchers.IO) {
                _uiState.update { it.copy(isLoading = true) }
                renderPageInternal(prevIndex)
            }
        }
    }

    /**
     * Toggles the current page index in the set of pages marked for deletion.
     */
    fun toggleMarkCurrentPageForDeletion() {
        _uiState.update { state ->
            val currentIndex = state.currentPageIndex
            val updatedSet = if (state.markedForDeletionPages.contains(currentIndex)) {
                state.markedForDeletionPages - currentIndex
            } else {
                state.markedForDeletionPages + currentIndex
            }
            state.copy(markedForDeletionPages = updatedSet)
        }
    }

    override fun onCleared() {
        // Release bitmap memory and close renderer resources
        _uiState.value.currentPageBitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
        try {
            pdfRenderer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PdfRenderer", e)
        }
        pdfRenderer = null

        try {
            parcelFileDescriptor?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ParcelFileDescriptor", e)
        }
        parcelFileDescriptor = null
    }

    companion object {
        private const val TAG = "PdfViewerViewModel"
    }
}

/**
 * Factory for creating [PdfViewerViewModel] instances.
 */
class PdfViewerViewModelFactory(
    private val application: Application,
    private val uri: Uri,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PdfViewerViewModel::class.java)) {
            return PdfViewerViewModel(application, uri) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
