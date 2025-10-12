package com.example.contacto.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.contacto.R
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    userName: String? = null,
    onRewriteNfcClick: () -> Unit,
    onOpenNfcReader: () -> Unit,
    onReadNowClick: () -> Unit,
    onOpenSettingsClick: () -> Unit
) {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) {
        in 5..11 -> stringResource(R.string.greeting_morning)
        in 12..19 -> stringResource(R.string.greeting_afternoon)
        else -> stringResource(R.string.greeting_evening)
    }

    Scaffold(
        containerColor = Color.White, // Fondo blanco global
        topBar = {
            CenterAlignedTopAppBar(
                title = { BrandTitle() },
                actions = {
                    IconButton(onClick = onOpenSettingsClick) {
                        Icon(imageVector = Icons.Filled.Settings, contentDescription = "Ajustes")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.White,
                    titleContentColor = Color(0xFF0E2138), // azul del logotipo
                    actionIconContentColor = Color(0xFF0E2138)
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
                    Image(
                        painter = painterResource(R.drawable.ic_logo_contacto),
                        contentDescription = stringResource(R.string.accessibility_app_logo),
                        modifier = Modifier.size(240.dp),
                        contentScale = ContentScale.Fit
                    )
                }

                Text(
                    text = buildString {
                        append(greeting)
                        userName?.takeIf { it.isNotBlank() }?.let { append(", $it") }
                    },
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )

                Button(
                    onClick = onReadNowClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Icon(Icons.Outlined.Contactless, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Leer NFC ahora")
                }

                Button(
                    onClick = onRewriteNfcClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Icon(Icons.Outlined.Contactless, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cta_rewrite_nfc))
                }
            }
        }
    }
}

@Composable
private fun BrandTitle() {
    // Si tienes un recurso de la palabra como imagen, reemplaza por Image(painterResource(R.drawable.logo_contacto_wordmark), ...)
    val brandColor = Color(0xFF0E2138)
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = brandColor, fontWeight = FontWeight.SemiBold)) { append("Con") }
            withStyle(SpanStyle(color = brandColor, fontWeight = FontWeight.ExtraBold)) { append("Tacto") }
        },
        style = MaterialTheme.typography.titleLarge
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun HomeScreenPreview() {
    MaterialTheme {
        HomeScreen(
            onRewriteNfcClick = {},
            onOpenNfcReader = {},
            onReadNowClick = {},
            onOpenSettingsClick = {}
        )
    }
}
