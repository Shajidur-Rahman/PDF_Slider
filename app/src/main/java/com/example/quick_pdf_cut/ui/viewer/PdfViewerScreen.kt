package com.example.quick_pdf_cut.ui.viewer

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Screen displaying PDF pages sequentially with controls for navigation and deletion marking.
 *
 * @param uri The [Uri] of the opened PDF document.
 * @param onFinish Action triggered when clicking "Next Step", passing the list of page indices to delete.
 * @param modifier Custom layout modifier.
 * @param viewModel The [PdfViewerViewModel] backing this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    uri: Uri,
    onFinish: (pagesToDelete: List<Int>) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PdfViewerViewModel = viewModel(
        factory = PdfViewerViewModelFactory(
            application = LocalContext.current.applicationContext as Application,
            uri = uri,
        ),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()
    val isCurrentPageMarked = uiState.markedForDeletionPages.contains(uiState.currentPageIndex)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    val pageText = if (uiState.totalPages > 0) {
                        "Page ${uiState.currentPageIndex + 1} of ${uiState.totalPages}"
                    } else {
                        "PDF Viewer"
                    }
                    Text(text = pageText)
                },
                actions = {
                    TextButton(
                        onClick = { onFinish(uiState.pagesToDelete) },
                        enabled = (!uiState.isLoading) && (uiState.totalPages > 0),
                    ) {
                        Text(text = "Next Step")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Central Area Displaying Current PDF Page
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator()
                    }

                    uiState.errorMessage != null -> {
                        Text(
                            text = uiState.errorMessage ?: "An error occurred",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp),
                        )
                    }

                    uiState.currentPageBitmap != null -> {
                        val bitmap = uiState.currentPageBitmap
                        if ((bitmap != null) && (!bitmap.isRecycled)) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "PDF Page ${uiState.currentPageIndex + 1}",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                )

                                // Overlay badge if marked for deletion
                                if (isCurrentPageMarked) {
                                    Box(
                                        modifier = Modifier
                                            .padding(8.dp)
                                            .background(
                                                color = MaterialTheme.colorScheme.errorContainer,
                                                shape = MaterialTheme.shapes.small,
                                            )
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = "Marked for Deletion",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Navigation & Actions Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Previous Button
                Button(
                    onClick = { viewModel.previousPage() },
                    enabled = (!uiState.isLoading) && (uiState.currentPageIndex > 0),
                ) {
                    Text(text = "Previous")
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Toggle "Delete Current Page" / "Marked for Deletion" Button
                if (isCurrentPageMarked) {
                    Button(
                        onClick = { viewModel.toggleMarkCurrentPageForDeletion() },
                        enabled = !uiState.isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(text = "Marked for Deletion")
                    }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.toggleMarkCurrentPageForDeletion() },
                        enabled = (!uiState.isLoading) && (uiState.totalPages > 0),
                    ) {
                        Text(text = "Delete Current Page")
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Next Button
                Button(
                    onClick = { viewModel.nextPage() },
                    enabled = (!uiState.isLoading) && (uiState.currentPageIndex < uiState.totalPages - 1),
                ) {
                    Text(text = "Next")
                }
            }
        }
    }
}
