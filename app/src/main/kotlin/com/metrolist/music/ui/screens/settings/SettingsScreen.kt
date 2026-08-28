package com.metrolist.music.ui.screens.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.metrolist.music.BuildConfig
import com.metrolist.music.R
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.ReleaseNotesCard
import com.metrolist.music.ui.utils.backToMain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    latestVersionName: String,
) {
    val context = LocalContext.current
    val isAndroid12OrLater = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val hasAndroidAuto = remember {
        try {
            context.packageManager.getPackageInfo("com.google.android.projection.gearhead", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.navigateUp() },
                        onLongClick = { navController.backToMain() }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = "Back",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 56.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 16.dp, bottom = 32.dp)
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column {
                    SettingsItem(
                        icon = painterResource(R.drawable.palette),
                        iconColor = Color(0xFF8B5E66),
                        title = stringResource(R.string.appearance),
                        subtitle = "Themes and visuals",
                        onClick = { navController.navigate("settings/appearance") }
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    SettingsItem(
                        icon = painterResource(R.drawable.play),
                        iconColor = Color(0xFF5E6D8B),
                        title = stringResource(R.string.player_and_audio),
                        subtitle = "Playback settings",
                        onClick = { navController.navigate("settings/player") }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    SettingsItem(
                        icon = painterResource(R.drawable.language),
                        iconColor = Color(0xFF6D8B7D),
                        title = stringResource(R.string.content),
                        subtitle = "Languages and regions",
                        onClick = { navController.navigate("settings/content") }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    SettingsItem(
                        icon = painterResource(R.drawable.translate),
                        iconColor = Color(0xFF6D8B7D),
                        title = stringResource(R.string.ai_lyrics_translation),
                        subtitle = "Translation settings",
                        onClick = { navController.navigate("settings/ai") }
                    )

                    if (hasAndroidAuto) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingsItem(
                            icon = painterResource(R.drawable.ic_android_auto),
                            iconColor = Color(0xFF5E6D8B),
                            title = stringResource(R.string.android_auto),
                            subtitle = "Car dashboard integration",
                            onClick = { navController.navigate("settings/android_auto") }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    
                    SettingsItem(
                        icon = painterResource(R.drawable.security),
                        iconColor = Color(0xFF5E6D8B),
                        title = stringResource(R.string.privacy),
                        subtitle = "Security and privacy",
                        onClick = { navController.navigate("settings/privacy") }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    SettingsItem(
                        icon = painterResource(R.drawable.storage),
                        iconColor = Color(0xFFD8B96D),
                        title = stringResource(R.string.storage),
                        subtitle = "Cache and local data",
                        onClick = { navController.navigate("settings/storage") }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    SettingsItem(
                        icon = painterResource(R.drawable.restore),
                        iconColor = Color(0xFFD8B96D),
                        title = stringResource(R.string.backup_restore),
                        subtitle = "Backup and restore data",
                        onClick = { navController.navigate("settings/backup_restore") }
                    )

                    if (isAndroid12OrLater) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingsItem(
                            icon = painterResource(R.drawable.link),
                            iconColor = Color(0xFF7D8B8B),
                            title = stringResource(R.string.default_links),
                            subtitle = "System app settings",
                            onClick = {
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                                        "package:${context.packageName}".toUri()
                                    )
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, R.string.open_app_settings_error, Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                    }

                    if (BuildConfig.UPDATER_AVAILABLE) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingsItem(
                            icon = painterResource(R.drawable.update),
                            iconColor = Color(0xFFD89B6D),
                            title = stringResource(R.string.updater),
                            subtitle = "Feature unavailable",
                            enabled = false,
                            onClick = { }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    
                    SettingsItem(
                        icon = painterResource(R.drawable.newspaper),
                        iconColor = Color(0xFF8B5E66),
                        title = stringResource(R.string.changelog),
                        subtitle = "Feature unavailable",
                        enabled = false,
                        onClick = { }
                    )
                }
            }

            if (BuildConfig.UPDATER_AVAILABLE && latestVersionName != BuildConfig.VERSION_NAME) {
                Spacer(modifier = Modifier.height(24.dp))
                ReleaseNotesCard()
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsItem(
    icon: Painter,
    iconColor: Color,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val contentAlpha = if (enabled) 1f else 0.38f
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(iconColor.copy(alpha = contentAlpha)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = contentAlpha),
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
            )
        }
    }
}
