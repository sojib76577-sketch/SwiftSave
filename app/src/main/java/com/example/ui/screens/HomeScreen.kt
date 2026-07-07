package com.example.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ads.AdManager
import com.example.data.database.DownloadTask
import com.example.ui.viewmodel.DownloadViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: DownloadViewModel,
    onNavigateToPlayer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current

    val urlInput by viewModel.urlInput.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isPremium by viewModel.isPremiumUnlocked.collectAsState()

    val downloadingTasks by viewModel.downloadingTasks.collectAsState()
    val completedTasks by viewModel.completedTasks.collectAsState()
    val failedTasks by viewModel.failedTasks.collectAsState()

    // Dialog state
    val showConfirmDialog by viewModel.showConfirmDialog.collectAsState()
    val confirmUrl by viewModel.confirmUrl.collectAsState()
    val confirmTitle by viewModel.confirmTitle.collectAsState()

    var showRenameDialogForTask by remember { mutableStateOf<DownloadTask?>(null) }
    var showDeleteDialogForTask by remember { mutableStateOf<DownloadTask?>(null) }
    var renameInputTitle by remember { mutableStateOf("") }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Downloading", "Completed", "Failed")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DownloadForOffline,
                            contentDescription = "Logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "VidFetch",
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        },
        bottomBar = {
            // Standard Start.io Banner Ad integrated at the bottom of home screen
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                    .navigationBarsPadding()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                StartAppBannerAd()
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Premium Speed / Rewarded Ad trigger Card
            PremiumSpeedCard(
                isPremium = isPremium,
                onWatchAdClick = {
                    viewModel.unlockPremiumViaRewardedAd(
                        context,
                        onSuccess = {
                            Toast.makeText(context, "Premium 8x Speed Mode Unlocked!", Toast.LENGTH_LONG).show()
                        },
                        onFailure = { error ->
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        }
                    )
                },
                onResetClick = {
                    viewModel.removePremiumState()
                    Toast.makeText(context, "Returned to Standard speed", Toast.LENGTH_SHORT).show()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Video URL Downloader Input Box
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Download New Video",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { viewModel.urlInput.value = it },
                            placeholder = { Text("Paste video link here...") },
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Default.Link, contentDescription = "Link Icon")
                            },
                            trailingIcon = {
                                if (urlInput.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.urlInput.value = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                } else {
                                    IconButton(
                                        onClick = {
                                            if (!view.hasWindowFocus()) {
                                                Toast.makeText(context, "App must be in focus to paste.", Toast.LENGTH_SHORT).show()
                                                return@IconButton
                                            }
                                            try {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clip = clipboard.primaryClip
                                                if (clip != null && clip.itemCount > 0) {
                                                    val pasteText = clip.getItemAt(0).text?.toString()
                                                    if (!pasteText.isNullOrBlank()) {
                                                        viewModel.urlInput.value = pasteText.trim()
                                                        Toast.makeText(context, "Link pasted!", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        Toast.makeText(context, "Clipboard is empty.", Toast.LENGTH_SHORT).show()
                                                    }
                                                } else {
                                                    Toast.makeText(context, "No text found in clipboard.", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Cannot read clipboard. Please paste manually.", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.testTag("paste_button")
                                    ) {
                                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste from Clipboard")
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    keyboardController?.hide()
                                    if (!viewModel.validateAndPrepareDownload(urlInput)) {
                                        Toast.makeText(context, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("url_input_field")
                        )

                        Button(
                            onClick = {
                                keyboardController?.hide()
                                if (!viewModel.validateAndPrepareDownload(urlInput)) {
                                    Toast.makeText(context, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(16.dp),
                            modifier = Modifier
                                .testTag("fetch_button")
                                .height(54.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Fetch")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Search Bar for downloaded items
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.searchQuery.value = it },
                placeholder = { Text("Search downloaded videos...") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_bar")
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Tabs Header
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    val badgeCount = when (index) {
                        0 -> downloadingTasks.size
                        1 -> completedTasks.size
                        2 -> failedTasks.size
                        else -> 0
                    }
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = title,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium
                                )
                                if (badgeCount > 0) {
                                    Badge(
                                        containerColor = if (selectedTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = if (selectedTab == index) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                                    ) {
                                        Text(text = badgeCount.toString())
                                    }
                                }
                            }
                        },
                        modifier = Modifier.testTag("tab_$index")
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // List of tasks based on selected tab
            val currentTasksList = when (selectedTab) {
                0 -> downloadingTasks
                1 -> completedTasks
                2 -> failedTasks
                else -> emptyList()
            }

            if (currentTasksList.isEmpty()) {
                EmptyStateView(
                    tabTitle = tabs[selectedTab],
                    hasSearch = searchQuery.isNotEmpty()
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("downloads_list")
                ) {
                    items(currentTasksList, key = { it.id }) { task ->
                        TaskItemRow(
                            task = task,
                            onPlayClick = {
                                onNavigateToPlayer(task.filePath)
                            },
                            onPauseClick = { viewModel.pauseDownload(context, task) },
                            onResumeClick = { viewModel.resumeDownload(context, task) },
                            onCancelClick = { viewModel.cancelDownload(context, task, deleteRecord = true) },
                            onRetryClick = { viewModel.retryDownload(context, task) },
                            onShareClick = { viewModel.shareTaskFile(context, task) },
                            onRenameClick = {
                                showRenameDialogForTask = task
                                renameInputTitle = task.title
                            },
                            onDeleteClick = { showDeleteDialogForTask = task }
                        )
                    }
                }
            }
        }
    }

    // 1. CONFIRM DOWNLOAD DIALOG (Safe validation & Custom Title Input before downloading)
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showConfirmDialog.value = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DownloadForOffline,
                        contentDescription = "Confirm",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Confirm Download")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "A downloadable stream is found. You can customize the video filename below before downloading:",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    OutlinedTextField(
                        value = confirmTitle,
                        onValueChange = { viewModel.confirmTitle.value = it },
                        label = { Text("Video Filename") },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "Source Link:\n$confirmUrl",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // After confirmation, start download and check if we show successful download count ad
                        viewModel.confirmDownload(context)
                    }
                ) {
                    Text("Start Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showConfirmDialog.value = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    // 2. RENAME COMPLETED FILE DIALOG
    if (showRenameDialogForTask != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialogForTask = null },
            title = { Text("Rename File") },
            text = {
                OutlinedTextField(
                    value = renameInputTitle,
                    onValueChange = { renameInputTitle = it },
                    label = { Text("Filename") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val task = showRenameDialogForTask
                        if (task != null && renameInputTitle.trim().isNotEmpty()) {
                            viewModel.renameTaskFile(context, task, renameInputTitle.trim())
                        }
                        showRenameDialogForTask = null
                    }
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialogForTask = null }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    // 3. DELETE TASK & FILE CONFIRM DIALOG
    if (showDeleteDialogForTask != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialogForTask = null },
            title = { Text("Delete Download") },
            text = {
                Text("Are you sure you want to delete this downloaded video from your history and storage?")
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    onClick = {
                        val task = showDeleteDialogForTask
                        if (task != null) {
                            viewModel.deleteTaskAndFile(context, task)
                        }
                        showDeleteDialogForTask = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialogForTask = null }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
fun PremiumSpeedCard(
    isPremium: Boolean,
    onWatchAdClick: () -> Unit,
    onResetClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isPremium) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            }
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.ElectricBolt,
                        contentDescription = "Bolt",
                        tint = if (isPremium) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isPremium) "8x Speed Enabled" else "Super Fast Downloads",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isPremium) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isPremium) {
                        "Unlocked for 24 hours. High-speed buffer enabled."
                    } else {
                        "Watch a quick video ad to unlock maximum multi-threaded high-speed downloading!"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPremium) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            if (isPremium) {
                IconButton(
                    onClick = onResetClick,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset Speed", tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                Button(
                    onClick = onWatchAdClick,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Unlock", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun TaskItemRow(
    task: DownloadTask,
    onPlayClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onCancelClick: () -> Unit,
    onRetryClick: () -> Unit,
    onShareClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Icon + Title + Info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            color = when (task.status) {
                                "COMPLETED" -> MaterialTheme.colorScheme.primaryContainer
                                "FAILED" -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.secondaryContainer
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                ) {
                    Icon(
                        imageVector = when (task.status) {
                            "COMPLETED" -> Icons.Default.PlayArrow
                            "FAILED" -> Icons.Default.ErrorOutline
                            else -> Icons.Default.Download
                        },
                        contentDescription = "Task State",
                        tint = when (task.status) {
                            "COMPLETED" -> MaterialTheme.colorScheme.onPrimaryContainer
                            "FAILED" -> MaterialTheme.colorScheme.onErrorContainer
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier
                            .size(24.dp)
                            .clickable(enabled = task.status == "COMPLETED") { onPlayClick() }
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = task.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Body content depends on Task status
            when (task.status) {
                "DOWNLOADING" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { task.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        )

                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val pct = (task.progress * 100).toInt()
                            val loadedStr = formatBytes(task.downloadedBytes)
                            val totalStr = formatBytes(task.totalBytes)
                            val speedStr = formatSpeed(task.speedBytesPerSec)
                            val etaStr = formatEta(task.etaSeconds)

                            Text(
                                text = "$pct% ($loadedStr / $totalStr)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text = "$speedStr • ETA $etaStr",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Controls
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(
                                onClick = onPauseClick,
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = "Pause", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Pause")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = onCancelClick,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Icon(Icons.Default.Cancel, contentDescription = "Cancel", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Cancel")
                            }
                        }
                    }
                }

                "PAUSED" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { task.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                        )

                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val pct = (task.progress * 100).toInt()
                            val loadedStr = formatBytes(task.downloadedBytes)
                            val totalStr = formatBytes(task.totalBytes)
                            Text(
                                text = "$pct% ($loadedStr / $totalStr) [Paused]",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(onClick = onResumeClick) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Resume", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Resume")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = onCancelClick,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete")
                            }
                        }
                    }
                }

                "QUEUED" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "Waiting in queue...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                "COMPLETED" -> {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Successfully Saved",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )

                        Row {
                            IconButton(onClick = onPlayClick) {
                                Icon(Icons.Outlined.PlayCircle, contentDescription = "Play", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = onShareClick) {
                                Icon(Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.secondary)
                            }
                            IconButton(onClick = onRenameClick) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = onDeleteClick) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                "FAILED" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = task.errorMessage ?: "An error occurred during transfer",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(onClick = onRetryClick) {
                                Icon(Icons.Default.Refresh, contentDescription = "Retry", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Retry")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = onDeleteClick,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Remove")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyStateView(tabTitle: String, hasSearch: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (hasSearch) Icons.Default.SearchOff else Icons.Outlined.Movie,
                contentDescription = "Empty icon",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(72.dp)
            )
            
            Text(
                text = if (hasSearch) "No Matching Videos Found" else "No Videos in $tabTitle",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = if (hasSearch) {
                    "Try revising your search query or clear the filter."
                } else {
                    when (tabTitle) {
                        "Downloading" -> "Enter a valid video URL at the top to start a high-speed download."
                        "Completed" -> "Once downloads complete, you can find them here to play, share, or manage."
                        else -> "Failed downloads due to connection interruptions will be saved here."
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.width(280.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
fun StartAppBannerAd(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            com.startapp.sdk.ads.banner.Banner(context).apply {
                // Any specific customization if required by layout parameters
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
    )
}

// Utility string formatters
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    val digitGroup = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val value = bytes / Math.pow(1024.0, digitGroup.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[digitGroup])
}

fun formatSpeed(speedBytesPerSec: Long): String {
    if (speedBytesPerSec <= 0) return "0 KB/s"
    return "${formatBytes(speedBytesPerSec)}/s"
}

fun formatEta(seconds: Long): String {
    if (seconds <= 0) return "--"
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    val secs = seconds % 60
    return "${minutes}m ${secs}s"
}
