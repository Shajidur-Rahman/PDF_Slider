package com.example.quick_pdf_cut.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.quick_pdf_cut.R

/**
 * Custom Material 3 TopAppBar displaying the inline "3-Up" neon blue schematic app icon
 * alongside the application title "PDF Studio Pro".
 *
 * @param title The title text to display next to the logo.
 * @param modifier Custom layout modifier.
 * @param actions Optional action composables displayed on the trailing edge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomAppTopBar(
    title: String = "PDF Studio Pro",
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_app_logo),
                    contentDescription = "App Logo",
                    tint = Color.Unspecified, // Preserves the vector logo's custom neon-blue colors
                    modifier = Modifier.size(32.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}
