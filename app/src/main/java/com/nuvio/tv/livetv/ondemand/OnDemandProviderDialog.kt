package com.nuvio.tv.livetv.ondemand

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.ui.LiveDialog
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.MenuItem
import com.nuvio.tv.livetv.ui.TextInputDialog
import com.nuvio.tv.livetv.ui.focusedSecondaryTextColor
import com.nuvio.tv.livetv.ui.guideSurface
import com.nuvio.tv.livetv.ui.guideSurfaceVariant
import com.nuvio.tv.livetv.ui.liveCellColors
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** For titles none of your addons know: the provider's own details, and Play. */
@Composable
internal fun ProviderDetailDialog(
    item: VodItem,
    viewModel: OnDemandViewModel,
    onDismiss: () -> Unit,
    onPlay: (url: String, title: String, poster: String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    val info by produceState<VodInfo?>(initialValue = null, item.uid) { value = viewModel.info(item) }
    val poster = item.icon
    var season by remember { mutableStateOf<Int?>(null) }
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 760.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(
                modifier = Modifier.width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(guideSurface()),
                contentAlignment = Alignment.Center
            ) {
                val img = poster ?: info?.cover
                if (!img.isNullOrBlank()) AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Column(modifier = Modifier.weight(1f)) {
                LiveText(OnDemandDatabase.displayTitle(item.name), size = 22.sp, weight = FontWeight.Bold, maxLines = 2)
                val meta = listOfNotNull(
                    item.year?.toString() ?: info?.releaseDate?.take(4),
                    info?.genre,
                    info?.durationText,
                    item.rating?.let { "★ $it" }
                ).joinToString("  ·  ")
                if (meta.isNotBlank()) LiveText(meta, color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                Spacer(Modifier.height(8.dp))
                LiveText(info?.plot ?: if (info == null) "Loading details…" else "", color = NuvioTheme.colors.TextSecondary, size = 13.sp, maxLines = 6)
                Spacer(Modifier.height(12.dp))
                if (item.kind == VodKind.MOVIE) {
                    val versions by produceState<List<OnDemandRepository.Version>>(initialValue = emptyList(), item.uid) {
                        value = viewModel.versions(item)
                    }
                    if (versions.size > 1) {
                        // Several copies: pick one (best quality first).
                        LiveText("Choose a version", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(versions, key = { it.item.uid }) { v ->
                                MenuItem(
                                    "Play · ${v.label}" + if (v.detail.isNotBlank()) "  —  ${v.detail}" else "",
                                    if (v == versions.first()) Modifier.focusRequester(first) else Modifier
                                ) {
                                    scope.launch { viewModel.movieUrl(v.item)?.let { onPlay(it, item.name, poster) } }
                                }
                            }
                        }
                    } else MenuItem("Play", Modifier.focusRequester(first)) {
                        scope.launch { viewModel.movieUrl(item)?.let { onPlay(it, item.name, poster) } }
                    }
                } else {
                    val episodes = info?.episodes.orEmpty()
                    val seasons = episodes.map { it.season }.distinct()
                    val current = season ?: seasons.firstOrNull()
                    if (seasons.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(seasons) { s ->
                                DialogPill("Season $s", s == current) { season = s }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(episodes.filter { it.season == current }, key = { it.id }) { ep ->
                                MenuItem(
                                    "${ep.episode}.  ${ep.title}",
                                    if (ep == episodes.firstOrNull { it.season == current }) Modifier.focusRequester(first) else Modifier
                                ) {
                                    onPlay(ep.url, "${item.name} · S${ep.season}E${ep.episode}", poster)
                                }
                            }
                        }
                    } else if (info != null) {
                        LiveText("The provider didn't list any episodes.", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                    }
                }
            }
        }
    }
    LaunchedEffect(info) { delay(80); runCatching { first.requestFocus() } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialogPill(label: String, selected: Boolean, icon: Boolean = false, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = liveCellColors(focused = focused, idle = if (selected) guideSurfaceVariant() else Color.Transparent)
    val shape = RoundedCornerShape(50)
    Row(
        modifier = (if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(shape)
            .background(colors.background)
            .border(1.dp, if (focused) colors.border else NuvioTheme.colors.Border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon) {
            Icon(Icons.Default.Search, contentDescription = null, tint = colors.text, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        LiveText(label, color = colors.text, size = 14.sp, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
