package com.example.quick_pdf_cut

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.quick_pdf_cut.ui.export.ExportOptionsScreen
import com.example.quick_pdf_cut.ui.sequence.ArrangePdfSequenceScreen
import com.example.quick_pdf_cut.ui.theme.Quick_PDF_CutTheme
import com.example.quick_pdf_cut.ui.viewer.PdfViewerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Quick_PDF_CutTheme {
                QuickPdfApp()
            }
        }
    }
}

/**
 * Main application composable handling state-based navigation across screens.
 */
@Composable
fun QuickPdfApp() {
    var selectedUris by rememberSaveable { mutableStateOf<List<Uri>>(emptyList()) }
    var mergedPdfUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var isExporting by rememberSaveable { mutableStateOf(false) }
    var pagesToDelete by rememberSaveable { mutableStateOf<List<Int>>(emptyList()) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        val screenModifier = Modifier.padding(innerPadding)

        if (selectedUris.isEmpty()) {
            HomeScreen(
                onPdfsSelected = { uris ->
                    selectedUris = uris
                    mergedPdfUri = null
                    isExporting = false
                    pagesToDelete = emptyList()
                },
                modifier = screenModifier,
            )
        } else if (mergedPdfUri == null) {
            ArrangePdfSequenceScreen(
                selectedUris = selectedUris,
                onProceed = { mergedUri ->
                    mergedPdfUri = mergedUri
                },
                onBack = {
                    selectedUris = emptyList()
                },
                modifier = screenModifier,
            )
        } else if (isExporting) {
            ExportOptionsScreen(
                uri = mergedPdfUri!!,
                pagesToDelete = pagesToDelete,
                modifier = screenModifier,
                onBack = { isExporting = false },
            )
        } else {
            PdfViewerScreen(
                uri = mergedPdfUri!!,
                onFinish = { selectedPages ->
                    pagesToDelete = selectedPages
                    isExporting = true
                },
                modifier = screenModifier,
            )
        }
    }
}

/**
 * Home screen presenting a centered Material 3 button to select multiple PDF documents.
 */
@Composable
fun HomeScreen(
    onPdfsSelected: (List<Uri>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            onPdfsSelected(uris)
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Button(
            onClick = {
                pdfPickerLauncher.launch(arrayOf("application/pdf"))
            },
        ) {
            Text(text = "Select PDFs")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    Quick_PDF_CutTheme {
        HomeScreen(onPdfsSelected = {})
    }
}
