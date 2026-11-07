package ph.financewallet.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ph.financewallet.app.ui.theme.LocalSpacing

/** Bottom padding that keeps the last list item clear of an extended FAB. */
val FAB_CLEARANCE = 96.dp

/**
 * The body of a screen: a padded list with even spacing and, when [fabLabel] is given, the screen's one "add" action as
 * an extended FAB. Every list screen uses it, so adding things always works the same way.
 */
@Composable
fun ListScreen(
    fabLabel: String? = null,
    onFab: () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val spacing = LocalSpacing.current
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = { if (fabLabel != null) AddFab(fabLabel, onFab) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = spacing.md,
                    end = spacing.md,
                    top = spacing.sm,
                    bottom = if (fabLabel != null) FAB_CLEARANCE else spacing.lg,
                ),
            verticalArrangement = Arrangement.spacedBy(spacing.sm + spacing.xs),
            content = content,
        )
    }
}

/** One card holding several rows separated by hairlines (lists of categories, report lines, insights). */
@Composable
fun <T> CardList(
    items: List<T>,
    modifier: Modifier = Modifier,
    row: @Composable (T) -> Unit,
) {
    SurfaceCard(modifier) {
        items.forEachIndexed { i, item ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            row(item)
        }
    }
}

/** Nothing-here-yet message inside a list: icon, short title, one explaining sentence. */
@Composable
fun EmptyCard(
    icon: ImageVector,
    title: String,
    message: String,
) {
    val spacing = LocalSpacing.current
    SurfaceCard {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.xl).semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            IconDisc(icon, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Small status label (Due today, Overdue, Paid off). The word carries the meaning; colour only supports it. */
@Composable
fun StatusPill(
    text: String,
    container: Color,
    content: Color,
) {
    val spacing = LocalSpacing.current
    Box(
        Modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = spacing.sm, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

/**
 * A horizontal bar filled to [fraction] (0..1). The fraction is display-only, computed from integer amounts by the
 * caller; no money arithmetic happens here.
 */
@Composable
fun ShareBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(f.coerceAtLeast(MIN_VISIBLE))
                    .height(BAR_HEIGHT)
                    .clip(shape)
                    .background(color),
            )
        }
    }
}

private val BAR_HEIGHT = 8.dp
private const val MIN_VISIBLE = 0.02f
