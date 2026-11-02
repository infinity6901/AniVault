package com.anivault.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.anivault.ui.theme.AniVaultColors
import com.anivault.ui.theme.AniVaultTheme
import com.anivault.ui.theme.AppMode

@Composable
fun SearchRoot(
    mode: AppMode,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    AniVaultTheme(currentMode = mode) {
        SearchScreen(
            mode      = mode,
            viewModel = viewModel,
            onBack    = onBack
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(
    mode: AppMode,
    viewModel: SearchViewModel,
    onBack: () -> Unit
) {
    val query    by viewModel.query.collectAsStateWithLifecycle()
    val uiState  by viewModel.uiState.collectAsStateWithLifecycle()
    val added    by viewModel.addedTitle.collectAsStateWithLifecycle()

    val accent   = MaterialTheme.colorScheme.primary
    val keyboard = LocalSoftwareKeyboardController.current
    val focus    = remember { FocusRequester() }
    val snackbar = remember { SnackbarHostState() }

    // Show snackbar when item added
    LaunchedEffect(added) {
        if (added != null) {
            snackbar.showSnackbar("\"$added\" added to vault")
            viewModel.onAddedTitleConsumed()
        }
    }

    // Auto-focus search bar on entry
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        containerColor = AniVaultColors.Background,
        snackbarHost   = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = accent
                        )
                    }
                },
                title = {
                    val label = if (mode == AppMode.ANIME) "Search Anime" else "Search H-Vault"
                    Text(label, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = accent)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AniVaultColors.Surface.copy(alpha = 0.97f)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // ── Search bar ─────────────────────────────────────────────────
            OutlinedTextField(
                value         = query,
                onValueChange = viewModel::onQueryChange,
                modifier      = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                placeholder   = {
                    Text(
                        if (mode == AppMode.ANIME) "Title, genre…" else "Title, studio…",
                        color = AniVaultColors.OnSurfaceMid
                    )
                },
                leadingIcon   = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = accent)
                },
                trailingIcon  = {
                    if (uiState is SearchUiState.Loading) {
                        CircularProgressIndicator(
                            modifier  = Modifier.size(20.dp),
                            color     = accent,
                            strokeWidth = 2.dp
                        )
                    }
                },
                singleLine    = true,
                shape         = RoundedCornerShape(12.dp),
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = accent,
                    unfocusedBorderColor = accent.copy(alpha = 0.35f),
                    cursorColor          = accent,
                    focusedTextColor     = AniVaultColors.OnSurface,
                    unfocusedTextColor   = AniVaultColors.OnSurface
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        viewModel.search(mode)
                    }
                )
            )

            Spacer(Modifier.height(8.dp))

            // Fallback source notice
            if (uiState is SearchUiState.Success) {
                val src = (uiState as SearchUiState.Success).fallbackSource
                if (src != null) {
                    Text(
                        text     = "Results from $src (hanime.tv had no results)",
                        fontSize = 11.sp,
                        color    = accent.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            // ── Body ───────────────────────────────────────────────────────
            when (val s = uiState) {
                SearchUiState.Idle    -> IdleHint(accent)
                SearchUiState.Loading -> LoadingIndicator(accent)
                is SearchUiState.Error   -> ErrorMessage(s.message, accent)
                is SearchUiState.Success -> {
                    if (s.results.isEmpty()) {
                        NoResults(accent)
                    } else {
                        ResultList(
                            results  = s.results,
                            accent   = accent,
                            onAdd    = { id ->
                                keyboard?.hide()
                                if (mode == AppMode.ANIME) viewModel.addAnimeToVault(id)
                                else viewModel.addHentaiToVault(id)
                            }
                        )
                    }
                }
            }
        }
    }
}

// ── Result list ────────────────────────────────────────────────────────────

@Composable
private fun ResultList(
    results: List<SearchResultItem>,
    accent: Color,
    onAdd: (String) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding      = PaddingValues(vertical = 8.dp)
    ) {
        items(results, key = { it.id }) { item ->
            ResultCard(item = item, accent = accent, onAdd = { onAdd(item.id) })
        }
    }
}

@Composable
private fun ResultCard(
    item: SearchResultItem,
    accent: Color,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AniVaultColors.Surface)
            .border(1.dp, accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment    = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Cover
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.coverUrl)
                .crossfade(true)
                .build(),
            contentDescription = item.title,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier
                .size(width = 56.dp, height = 80.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AniVaultColors.Background)
        )

        // Text info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text       = item.title,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis,
                fontSize   = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color      = AniVaultColors.OnSurface
            )
            Text(
                text     = item.subtitle,
                fontSize = 11.sp,
                color    = AniVaultColors.OnSurfaceMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Add button
        IconButton(
            onClick  = onAdd,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.15f))
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add to vault",
                tint               = accent,
                modifier           = Modifier.size(20.dp)
            )
        }
    }
}

// ── State composables ──────────────────────────────────────────────────────

@Composable
private fun IdleHint(accent: Color) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(
            "Type a title and press Search",
            color    = AniVaultColors.OnSurfaceMid,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun LoadingIndicator(accent: Color) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        CircularProgressIndicator(color = accent)
    }
}

@Composable
private fun NoResults(accent: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        Alignment.Center
    ) {
        Text(
            "No results found",
            color    = AniVaultColors.OnSurfaceMid,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun ErrorMessage(message: String, accent: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        Alignment.Center
    ) {
        Text(
            "Error: $message",
            color    = MaterialTheme.colorScheme.error,
            fontSize = 13.sp
        )
    }
}
