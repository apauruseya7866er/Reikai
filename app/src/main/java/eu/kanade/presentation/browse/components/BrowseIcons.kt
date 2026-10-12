package eu.kanade.presentation.browse.components

import android.util.DisplayMetrics
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.coil.InstalledExtensionIcon
import eu.kanade.tachiyomi.data.coil.InstalledSourceIcon
import eu.kanade.tachiyomi.extension.model.Extension
import exh.assets.BuiltInSourceLogo
import exh.assets.builtInSourceLogo
import exh.assets.painter
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.roundedfilled.Dangerous
import mihon.icons.materialsymbols.roundedfilled.Warning
import tachiyomi.domain.source.model.Source
import tachiyomi.source.local.isLocal

private val defaultModifier = Modifier
    .height(40.dp)
    .aspectRatio(1f)

@Composable
fun SourceIcon(
    source: Source,
    modifier: Modifier = Modifier,
) {
    val builtInLogo = builtInSourceLogo(source.id) // RK

    when {
        source.isStub -> {
            Image(
                imageVector = MaterialSymbols.RoundedFilled.Warning,
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.error),
                modifier = modifier.then(defaultModifier),
            )
        }
        // RK -->
        // Built-in adult sources ship no extension icon, so they draw their bundled logo unless an
        // installed extension provides the source and has an icon of its own.
        builtInLogo != null -> {
            var hasNoIcon by remember(source.id) { mutableStateOf(false) }
            if (hasNoIcon) {
                BuiltInSourceLogoTile(builtInLogo, modifier)
            } else {
                AsyncImage(
                    model = InstalledSourceIcon(source.id),
                    contentDescription = null,
                    onError = { hasNoIcon = true },
                    modifier = modifier.then(defaultModifier),
                )
            }
        }
        // RK <--
        source.isLocal() -> {
            Image(
                painter = painterResource(R.mipmap.ic_local_source),
                contentDescription = null,
                modifier = modifier.then(defaultModifier),
            )
        }
        else -> {
            val defaultIcon = painterResource(R.mipmap.ic_default_source)
            AsyncImage(
                model = InstalledSourceIcon(source.id),
                contentDescription = null,
                placeholder = defaultIcon,
                error = defaultIcon,
                modifier = modifier.then(defaultModifier),
            )
        }
    }
}

// RK -->
// The 5dp inset matches the transparent safe-zone margin baked into extension launcher icons, so the
// tile reads the same size as its neighbours instead of full-bleed.
@Composable
private fun BuiltInSourceLogoTile(logo: BuiltInSourceLogo, modifier: Modifier) {
    val tile = modifier
        .then(defaultModifier)
        .padding(5.dp)
        .clip(RoundedCornerShape(2.dp))
    if (logo.isTiled) {
        Box(modifier = tile.background(Color.White), contentAlignment = Alignment.Center) {
            Image(
                painter = logo.painter(),
                contentDescription = null,
                // The EH mark is a bare vector with no margin of its own.
                modifier = Modifier.fillMaxSize(if (logo == BuiltInSourceLogo.EHENTAI) 0.72f else 1f),
            )
        }
    } else {
        Image(painter = logo.painter(), contentDescription = null, modifier = tile)
    }
}
// RK <--

@Composable
fun ExtensionIcon(
    extension: Extension,
    modifier: Modifier = Modifier,
    density: Int = DisplayMetrics.DENSITY_DEFAULT,
) {
    when (extension) {
        is Extension.Available -> {
            AsyncImage(
                model = extension.iconUrl,
                contentDescription = null,
                placeholder = ColorPainter(Color(0x1F888888)),
                // RK: the installed rows' fallback, since many stores publish no icon for some extensions
                error = painterResource(R.mipmap.ic_default_source),
                modifier = modifier
                    .clip(MaterialTheme.shapes.extraSmall),
            )
        }
        is Extension.Loaded -> {
            val defaultIcon = painterResource(R.mipmap.ic_default_source)
            AsyncImage(
                model = InstalledExtensionIcon(extension.pkgName, extension.versionCode, density),
                contentDescription = null,
                placeholder = defaultIcon,
                error = defaultIcon,
                modifier = modifier,
            )
        }
        is Extension.NotLoaded -> Image(
            imageVector = MaterialSymbols.RoundedFilled.Dangerous,
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.error),
            modifier = modifier.then(defaultModifier),
        )
    }
}
