package com.tprlab.teletube

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun Feed(
    modifier: Modifier,
    entries: List<FeedEntry>?,
    filters: List<ChannelRecord>,
    sync: SyncStatus,
    lastSynced: Long,
    hasSources: Boolean,
    showViewed: Boolean,
    onToggleViewed: () -> Unit,
    onFilter: () -> Unit,
    onSources: (() -> Unit)?,
    onReviewHidden: (() -> Unit)?,
    onRefresh: (() -> Unit)?,
    onPlay: (FeedEntry) -> Unit,
    onPost: (FeedEntry) -> Unit,
    onHideChannel: (Long) -> Unit,
    onHideVideo: (FeedEntry) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val filterIds = remember(filters) { filters.map { it.id }.toSet() }
    val byDate = remember(entries, filterIds, showViewed, zone) {
        entries.orEmpty().asSequence()
            .filter { filterIds.isEmpty() || it.channelId in filterIds }
            .filter { showViewed || !it.viewed }
            .groupBy { Instant.ofEpochSecond(it.postedAt).atZone(zone).toLocalDate() }
    }
    Column(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filters.isNotEmpty(),
                    onClick = onFilter,
                    enabled = hasSources,
                    label = {
                        val label = when (filters.size) {
                            0 -> "All channels"
                            1 -> filters.single().title
                            else -> "${filters.size} channels"
                        }
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Filter by channel") },
                    modifier = Modifier.widthIn(max = 280.dp).heightIn(min = 48.dp),
                )
                FilterChip(
                    selected = showViewed,
                    onClick = onToggleViewed,
                    label = {
                        Icon(painterResource(R.drawable.ic_viewed), contentDescription = "Show viewed", Modifier.size(20.dp))
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
            val statusText = sync.detail ?: if (lastSynced != 0L) {
                val moment = Instant.ofEpochSecond(lastSynced).atZone(zone)
                val date = if (moment.toLocalDate() == today) "today" else moment.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                "Last synced $date at ${moment.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}"
            } else null
            statusText?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SyncIssue(sync, onRefresh)
        }
        HorizontalDivider()
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = { onRefresh?.invoke() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                if (byDate.isEmpty()) {
                    item {
                        val noVisibleChannels = !hasSources && onReviewHidden != null
                        val noUnviewedVideos = !showViewed && entries.orEmpty().any {
                            filterIds.isEmpty() || it.channelId in filterIds
                        }
                        val scanningForFirstTime = sync.active && lastSynced == 0L && !noVisibleChannels && !noUnviewedVideos
                        Column(
                            Modifier.padding(horizontal = 24.dp, vertical = 40.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                when {
                                    entries == null -> "Loading saved links"
                                    scanningForFirstTime -> "Looking for videos"
                                    noVisibleChannels -> "No visible channels"
                                    noUnviewedVideos -> "No unviewed videos"
                                    !hasSources -> "No channels to scan"
                                    else -> "No videos found"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { heading() },
                            )
                            if (entries != null && !scanningForFirstTime) {
                                Text(
                                    if (noVisibleChannels) "Unhide a channel to see its saved links again."
                                    else if (noUnviewedVideos) "Tap the eye icon to show videos you’ve opened."
                                    else if (!hasSources) "Check which Telegram channels are selected."
                                    else if (filters.isNotEmpty()) "There are no imported links from the selected channels."
                                    else "YouTube links appear here after a scan finds them in channel posts.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (noVisibleChannels) {
                                    TextButton(onClick = onReviewHidden) { Text("Review hidden channels") }
                                } else if (!hasSources && onSources != null) {
                                    TextButton(onClick = onSources) { Text("Scan channels") }
                                }
                            }
                        }
                    }
                }
                byDate.forEach { (date, posts) ->
                    item(key = "date-$date") {
                        DateHeading(date, today)
                    }
                    items(posts, key = { "${it.channelId}:${it.messageId}:${it.videoId}" }) { entry ->
                        VideoRow(entry, zone, onPlay, onPost, onHideChannel, onHideVideo)
                    }
                }
            }
        }
    }
}

@Composable
private fun DateHeading(date: LocalDate, today: LocalDate) {
    val label = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
    }
    Text(
        label,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun VideoRow(
    entry: FeedEntry,
    zone: ZoneId,
    onPlay: (FeedEntry) -> Unit,
    onPost: (FeedEntry) -> Unit,
    onHideChannel: (Long) -> Unit,
    onHideVideo: (FeedEntry) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val time = Instant.ofEpochSecond(entry.postedAt).atZone(zone)
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    PlaybackRow(entry.title, entry.channelTitle, time, viewed = entry.viewed, onPlay = { onPlay(entry) }) {
        Box(Modifier.padding(end = 8.dp)) {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, "Options for ${entry.title}")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open Telegram post") }, onClick = {
                    menu = false
                    onPost(entry)
                })
                DropdownMenuItem(text = { Text("Hide video") }, onClick = {
                    menu = false
                    onHideVideo(entry)
                })
                DropdownMenuItem(text = { Text("Hide channel") }, onClick = {
                    menu = false
                    onHideChannel(entry.channelId)
                })
            }
        }
    }
}

@Composable
private fun PlaybackRow(
    title: String,
    channelTitle: String,
    time: String,
    viewed: Boolean = false,
    onPlay: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(if (viewed) colors.surfaceContainerLow else colors.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Play video", onClick = onPlay)
                .padding(start = 20.dp, top = 10.dp, bottom = 10.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.size(width = 60.dp, height = 42.dp),
                color = if (viewed) colors.secondaryContainer else colors.primaryContainer,
                contentColor = if (viewed) colors.onSecondaryContainer else colors.onPrimaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, null) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (viewed) {
                        Icon(
                            painterResource(R.drawable.ic_viewed), "Viewed",
                            Modifier.size(15.dp), tint = colors.onSurfaceVariant,
                        )
                    }
                    Text(
                        "$channelTitle · $time",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        actions()
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colors.outlineVariant)
}

@Composable
internal fun PlaybackHistory(modifier: Modifier, history: List<Playback>?, onPlay: (Playback) -> Unit) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val byDate = remember(history, zone) {
        history.orEmpty().groupBy { Instant.ofEpochMilli(it.playedAt).atZone(zone).toLocalDate() }
    }
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    LazyColumn(modifier, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        if (byDate.isEmpty()) {
            item {
                Text(
                    if (history == null) "Loading history" else "Videos you play from TeleTube appear here.",
                    Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        byDate.forEach { (date, plays) ->
            item(key = "date-$date") { DateHeading(date, today) }
            items(plays, key = { it.id }) { play ->
                val time = Instant.ofEpochMilli(play.playedAt).atZone(zone).format(timeFormat)
                PlaybackRow(play.title, play.channelTitle, time, onPlay = { onPlay(play) })
            }
        }
    }
}

@Composable
internal fun ChooseSources(
    modifier: Modifier,
    channels: List<ChannelRecord>,
    current: ScanScope?,
    sync: SyncStatus,
    onRetry: () -> Unit,
    onSave: (ScanScope) -> Unit,
) {
    var all by rememberSaveable(current) { mutableStateOf(current?.allChannels ?: true) }
    var selected by rememberSaveable(current) {
        mutableStateOf(current?.selected?.toList().orEmpty())
    }
    var search by rememberSaveable { mutableStateOf("") }
    val visible = remember(channels, search) { channels.filter { it.title.contains(search.trim(), ignoreCase = true) } }
    val availableSelection = channels.count { it.id in selected }
    val enabled = if (all) channels.isNotEmpty() else availableSelection != 0
    Column(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val subtitle = if (current == null) "Choose where to look for YouTube links. Your first scan covers the last 30 days."
            else "Choose which channels to include in future scans."
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                OptionRow("All channels", all, onSelect = { all = true })
                OptionRow("Selected channels", !all, onSelect = { all = false })
            }
            if (channels.isNotEmpty()) ChannelSearch(search) { search = it }
            SyncIssue(sync, onRetry)
        }
        HorizontalDivider()
        LazyColumn(contentPadding = PaddingValues(vertical = 6.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (visible.isEmpty()) {
                item {
                    val message = when {
                        sync.active && channels.isEmpty() -> "Finding Telegram channels…"
                        search.isNotBlank() -> "No matching channels"
                        else -> "No Telegram channels found. Join a channel in Telegram and refresh here."
                    }
                    Text(message, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (channels.isEmpty() && !sync.active && sync.error == null) {
                        TextButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text("Refresh channels")
                        }
                    }
                }
            }
            items(visible, key = { it.id }) { channel ->
                if (all) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(channel.title, style = MaterialTheme.typography.bodyLarge)
                        if (channel.archived) ChannelNote("Archived")
                    }
                } else {
                    ChannelCheckbox(channel.title, channel.id in selected, archived = channel.archived) { checked ->
                        selected = if (checked) selected + channel.id else selected - channel.id
                    }
                }
            }
        }
        HorizontalDivider()
        Button(
            onClick = { onSave(ScanScope(all, selected.toSet())) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                .heightIn(min = 48.dp),
        ) {
            Text(if (all) "Scan all channels" else "Scan selected channels ($availableSelection)")
        }
    }
}

@Composable
internal fun ChannelFilter(
    modifier: Modifier,
    channels: List<ChannelRecord>,
    current: List<Long>,
    onApply: (List<Long>) -> Unit,
) {
    var selected by rememberSaveable(current) { mutableStateOf(current) }
    var search by rememberSaveable { mutableStateOf("") }
    val shown = remember(channels, search) { channels.filter { it.title.contains(search.trim(), ignoreCase = true) } }
    val available = remember(channels) { channels.map { it.id }.toSet() }
    val selectedIds = remember(selected, available) { selected.filter { it in available }.toSet() }
    Column(modifier) {
        Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
            ChannelSearch(search) { search = it }
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item { ChannelCheckbox("All channels", selectedIds.isEmpty()) { selected = emptyList() } }
            items(shown, key = { it.id }) { channel ->
                ChannelCheckbox(channel.title, channel.id in selectedIds, archived = channel.archived) { checked ->
                    selected = if (checked) (selected + channel.id).distinct() else selected - channel.id
                }
            }
            if (shown.isEmpty()) item { Text("No matching channels", Modifier.padding(20.dp)) }
        }
        HorizontalDivider()
        Button(
            onClick = { onApply(selectedIds.toList()) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 48.dp),
        ) {
            Text(if (selectedIds.isEmpty()) "Show all channels" else "Apply filter (${selectedIds.size})")
        }
    }
}

@Composable
private fun ChannelCheckbox(
    title: String,
    checked: Boolean,
    archived: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp)
            .toggleable(checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (archived) ChannelNote("Archived")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HiddenItems(
    modifier: Modifier,
    channels: List<ChannelRecord>,
    videos: List<HiddenVideo>,
    onUnhideChannel: (Long) -> Unit,
    onUnhideVideo: (HiddenVideo) -> Unit,
) {
    var showVideos by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        PrimaryTabRow(selectedTabIndex = if (showVideos) 1 else 0) {
            Tab(selected = !showVideos, onClick = { showVideos = false }, text = { Text("Channels") })
            Tab(selected = showVideos, onClick = { showVideos = true }, text = { Text("Videos") })
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (showVideos) {
                if (videos.isEmpty()) item { HiddenEmptyState("No hidden videos") }
                items(videos, key = { it.videoId }) { video ->
                    HiddenItemRow(video.title) { onUnhideVideo(video) }
                }
            } else {
                if (channels.isEmpty()) item { HiddenEmptyState("No hidden channels") }
                items(channels, key = { it.id }) { channel ->
                    HiddenItemRow(channel.title) { onUnhideChannel(channel.id) }
                }
            }
        }
    }
}

@Composable
private fun HiddenEmptyState(message: String) {
    Text(message, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun HiddenItemRow(title: String, onUnhide: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onUnhide) { Text("Unhide") }
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onSelect: () -> Unit, padding: Int = 0) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = padding.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ChannelSearch(search: String, onSearch: (String) -> Unit) {
    OutlinedTextField(
        value = search, onValueChange = onSearch, label = { Text("Search channels") },
        leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ChannelNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SyncIssue(sync: SyncStatus, onRetry: (() -> Unit)?) {
    sync.error?.let {
        Text(
            it,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (onRetry != null) TextButton(onClick = onRetry, enabled = !sync.active) { Text("Retry") }
    }
}
