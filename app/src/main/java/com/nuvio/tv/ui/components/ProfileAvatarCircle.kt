package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.ui.theme.NuvioPrimitives

fun avatarShowsPlaceholder(imageUrl: String?, imageLoaded: Boolean): Boolean =
    imageUrl.isNullOrBlank() || !imageLoaded

@Composable
fun ProfileAvatarCircle(
    name: String,
    colorHex: String,
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    isSelected: Boolean = false,
    avatarImageUrl: String? = null,
    imageCrossfade: Boolean = true
) {
    if (com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current != null) {
        com.nuvio.tv.ui.v2.components.NuvioAvatar(
            avatar = com.nuvio.tv.ui.v2.components.AvatarModel(name, name, colorHex, avatarImageUrl),
            size = size, selected = isSelected, modifier = modifier
        )
        return
    }
    val avatarColor = runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
        .getOrDefault(NuvioPrimitives.blue500)
    val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val fontSize = (size.value * 0.4f).sp
    val context = LocalContext.current
    val imageRequest = remember(context, avatarImageUrl, imageCrossfade) {
        avatarImageUrl?.takeIf(String::isNotBlank)?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(imageCrossfade)
                .build()
        }
    }
    var imageLoaded by remember(imageRequest) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarColor, CircleShape)
            .then(
                if (isSelected) {
                    Modifier.border(3.dp, Color.White, CircleShape)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (avatarShowsPlaceholder(avatarImageUrl, imageLoaded)) {
            Text(
                text = initial,
                color = Color.White,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold
            )
        }
        if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = name,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
                onState = { imageLoaded = it is AsyncImagePainter.State.Success },
                contentScale = ContentScale.Crop
            )
        }
    }
}
