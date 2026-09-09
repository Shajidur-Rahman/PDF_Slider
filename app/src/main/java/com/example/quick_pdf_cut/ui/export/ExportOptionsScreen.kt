package com.example.quick_pdf_cut.ui.export

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.quick_pdf_cut.ui.theme.Quick_PDF_CutTheme
import com.example.quick_pdf_cut.worker.PdfProcessingWorker
import java.util.UUID

/**
 * Screen for configuring export options and triggering background PDF WorkManager processing.
 *
 * @param uri The original PDF document [Uri].
 * @param pagesToDelete List of zero-based page indices selected for deletion.
 * @param modifier Custom layout modifier.
 * @param onBack Callback to return to the PDF viewer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportOptionsScreen(
    uri: Uri,
    pagesToDelete: List<Int>,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val workManager = remember { WorkManager.getInstance(context) }

    var invertColors by rememberSaveable { mutableStateOf(false) }
    var make3Up by rememberSaveable { mutableStateOf(false) }
    var destinationUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var activeWorkIdString by rememberSaveable { mutableStateOf<String?>(null) }

    // Request notification permission launcher for Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* Permission result handled */ }

    // SAF launcher for creating output document
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
    ) { createdUri: Uri? ->
        createdUri?.let { destinationUri = it }
    }

    // Observe active WorkManager task flow
    val workId = activeWorkIdString?.let { try { UUID.fromString(it) } catch (_: Exception) { null } }
    val workInfo by if (workId != null) {
        workManager.getWorkInfoByIdFlow(workId).collectAsState(initial = null)
    } else {
        remember { mutableStateOf(null) }
    }

    val isProcessing = (workInfo?.state == WorkInfo.State.RUNNING) || (workInfo?.state == WorkInfo.State.ENQUEUED)
    val progressMessage = workInfo?.progress?.getString(PdfProcessingWorker.KEY_PROGRESS_MESSAGE) ?: "Processing PDF in background..."
    val progressCurrent = workInfo?.progress?.getInt(PdfProcessingWorker.KEY_PROGRESS_CURRENT, 0) ?: 0
    val progressTotal = workInfo?.progress?.getInt(PdfProcessingWorker.KEY_PROGRESS_TOTAL, 0) ?: 0

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Export Options") },
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
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Export Options",
                    style = MaterialTheme.typography.headlineMedium,
                )

                // WorkManager Task Status Card
                when (workInfo?.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        val resultMsg = workInfo?.outputData?.getString(PdfProcessingWorker.KEY_RESULT_MESSAGE)
                            ?: "PDF Export completed successfully."
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Text(
                                text = "Success: $resultMsg",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }

                    WorkInfo.State.FAILED -> {
                        val errorMsg = workInfo?.outputData?.getString(PdfProcessingWorker.KEY_ERROR_MESSAGE)
                            ?: "PDF Export failed."
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Text(
                                text = "Error: $errorMsg",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }

                    else -> {}
                }

                // Source File Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Source PDF URI:",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = uri.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                // Pages to Delete Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Pages Selected for Deletion:",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        if (pagesToDelete.isEmpty()) {
                            Text(
                                text = "No pages marked for deletion.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                text = "Zero-based Indices: ${pagesToDelete.joinToString(", ")}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Page Numbers (1-based): ${pagesToDelete.joinToString(", ") { (it + 1).toString() }}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                // 3-Up Layout Toggle Row
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isProcessing) {
                                make3Up = !make3Up
                            }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Create 3-Up Layout (3 slides per page)",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "Stacks 3 slides vertically on standard A4 pages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = make3Up,
                            onCheckedChange = { make3Up = it },
                            enabled = !isProcessing,
                        )
                    }
                }

                // Invert PDF Colors Toggle Row
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isProcessing) {
                                invertColors = !invertColors
                            }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Invert PDF Colors",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "Inverts document color palette for dark mode reading",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = invertColors,
                            onCheckedChange = { invertColors = it },
                            enabled = !isProcessing,
                        )
                    }
                }

                // Select Save Location Button
                OutlinedButton(
                    onClick = {
                        createDocumentLauncher.launch("modified_document.pdf")
                    },
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "Select Save Location")
                }

                // Destination Path Card
                val destUri = destinationUri
                if (destUri != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Destination Selected:",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = destUri.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                // Process and Save PDF Button
                Button(
                    onClick = {
                        val finalDest = destinationUri ?: return@Button

                        // Check notification permission for Android 13+
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }

                        val inputData = workDataOf(
                            PdfProcessingWorker.KEY_ORIGINAL_URI to uri.toString(),
                            PdfProcessingWorker.KEY_DESTINATION_URI to finalDest.toString(),
                            PdfProcessingWorker.KEY_PAGES_TO_DELETE to pagesToDelete.toIntArray(),
                            PdfProcessingWorker.KEY_INVERT_COLORS to invertColors,
                            PdfProcessingWorker.KEY_MAKE_3UP to make3Up,
                        )

                        val workRequest = OneTimeWorkRequestBuilder<PdfProcessingWorker>()
                            .setInputData(inputData)
                            .build()

                        workManager.enqueueUniqueWork(
                            "pdf_export_work",
                            ExistingWorkPolicy.REPLACE,
                            workRequest,
                        )

                        activeWorkIdString = workRequest.id.toString()
                        Toast.makeText(context, "Export started in background!", Toast.LENGTH_SHORT).show()
                    },
                    enabled = (destinationUri != null) && (!isProcessing),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "Process and Save PDF")
                }

                // Back to Viewer Button
                OutlinedButton(
                    onClick = onBack,
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "Back to Viewer")
                }
            }

            // Full-screen Loading Overlay during WorkManager execution
            if (isProcessing) {
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
                                text = progressMessage,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (progressTotal > 0) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { progressCurrent.toFloat() / progressTotal.toFloat() },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ExportOptionsScreenPreview() {
    Quick_PDF_CutTheme {
        ExportOptionsScreen(
            uri = "content://com.android.providers.downloads.documents/document/1".toUri(),
            pagesToDelete = listOf(0, 2, 4),
        )
    }
}
