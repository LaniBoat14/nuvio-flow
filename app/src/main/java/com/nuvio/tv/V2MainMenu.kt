package com.nuvio.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.screens.settings.settingsFocusFillColor
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.brand.ArcBrandArt
import com.nuvio.tv.ui.components.BrandMark
import com.nuvio.tv.ui.components.BrandWordmark
import com.nuvio.tv.ui.util.contentTextDirection
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioV2Focus

// One geometry for the open menu and the closed icons, so rows and icons share their heights.
private val MenuVerticalPadding = 28.dp
private val MenuRowHeight = 54.dp
private val MenuRowGap = 4.dp
private val MenuStartPadding = 20.dp
private val MenuWordmarkHeight = 26.dp
private val MenuTextShadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), 8f)

/**
 * Row height that keeps the centred group clear of the profile and the logo, for example with six pages at the
 * largest UI scale. [innerHeight] is the menu height inside its vertical padding.
 */
internal fun menuRowHeight(innerHeight: Dp, rows: Int): Dp {
    if (rows <= 0) return MenuRowHeight
    val group = innerHeight - (MenuRowHeight + 8.dp) * 2
    return ((group - MenuRowGap * (rows - 1)) / rows).coerceIn(40.dp, MenuRowHeight)
}

/**
 * The V2 main menu: profile at the top, the pages as one group centred on the screen height,
 * the Nuvio logo at the bottom. [onPicture] false keeps the solid panel of Pure Liquid Dark.
 */
@Composable
internal fun V2MainMenuPanel(
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    focusEnabled: Boolean,
    sidebarExpandProgress: () -> Float,
    onPicture: Boolean,
    drawerItemFocusRequesters: Map<String, FocusRequester>,
    profileFocusRequester: FocusRequester,
    onDrawerItemFocused: (Int) -> Unit,
    onDrawerItemClick: (String) -> Unit,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .graphicsLayer { alpha = sidebarExpandProgress() }
            .then(if (onPicture) Modifier else Modifier.nuvioGlass(GlassRole.NAVIGATION, shape = RectangleShape, trailingEdgeOnly = true))
            .padding(start = MenuStartPadding, end = 12.dp, top = MenuVerticalPadding, bottom = MenuVerticalPadding)
    ) {
        if (showProfileSelector && activeProfileName.isNotEmpty()) {
            V2MainMenuProfileRow(
                name = activeProfileName,
                colorHex = activeProfileColorHex,
                avatarImageUrl = activeProfileAvatarImageUrl,
                focusEnabled = focusEnabled,
                onPicture = onPicture,
                onFocused = { onDrawerItemFocused(drawerItems.size) },
                onClick = onSwitchProfile,
                modifier = Modifier.align(Alignment.TopStart).focusRequester(profileFocusRequester)
            )
        }
        val rowHeight = menuRowHeight(maxHeight, drawerItems.size)
        Column(
            modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(MenuRowGap)
        ) {
            drawerItems.forEachIndexed { index, item ->
                androidx.compose.runtime.key(item.route) {
                    V2MainMenuRow(
                        label = item.label,
                        iconRes = item.iconRes,
                        icon = item.icon,
                        selected = selectedDrawerRoute == item.route,
                        focusEnabled = focusEnabled,
                        onPicture = onPicture,
                        height = rowHeight,
                        onFocused = { onDrawerItemFocused(index) },
                        onClick = { onDrawerItemClick(item.route) },
                        modifier = Modifier.focusRequester(drawerItemFocusRequesters.getValue(item.route))
                    )
                }
            }
        }
        BrandWordmark(
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp)
                .height(MenuWordmarkHeight)
                .aspectRatio(ArcBrandArt.LockupAspect)
        )
    }
}

/** The closed menu: the same icons at the height of their open rows, nothing focusable. */
@Composable
internal fun V2MainMenuRail(
    items: List<DrawerItem>,
    selectedRoute: String?,
    showProfile: Boolean,
    profileName: String,
    profileColorHex: String,
    profileAvatarImageUrl: String?,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(62.dp)
            .focusProperties { canFocus = false }
            .clickable(onClick = onExpand)
            .padding(top = MenuVerticalPadding, bottom = MenuVerticalPadding)
    ) {
        if (showProfile && profileName.isNotEmpty()) {
            Box(Modifier.align(Alignment.TopStart).height(MenuRowHeight).padding(start = 17.dp), contentAlignment = Alignment.CenterStart) {
                ProfileAvatarCircle(name = profileName, colorHex = profileColorHex, size = 28.dp,
                    avatarImageUrl = profileAvatarImageUrl, imageCrossfade = false)
            }
        }
        val rowHeight = menuRowHeight(maxHeight, items.size)
        Column(Modifier.align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(MenuRowGap)) {
            items.forEach { item ->
                val selected = item.route == selectedRoute
                Row(Modifier.height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(10.dp))
                    MenuCurrentPageBar(selected)
                    Spacer(Modifier.width(9.dp))
                    DrawerItemIcon(iconRes = item.iconRes, icon = item.icon, modifier = Modifier.size(18.dp),
                        tint = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary)
                }
            }
        }
        BrandMark(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 21.dp, bottom = 2.dp).height(22.dp)
                .aspectRatio(ArcBrandArt.MarkAspect)
        )
    }
}

/** Soft dark shade from the menu's edge; [alpha] is read while drawing only. */
@Composable
internal fun V2MainMenuShade(alpha: () -> Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight().drawWithCache {
        val rtl = layoutDirection == LayoutDirection.Rtl
        // Solid under the labels (they end before 0.7 of the open width), gone where the page starts.
        val brush = Brush.horizontalGradient(
            0f to Color.Black, 0.7f to Color.Black, 1f to Color.Transparent,
            startX = if (rtl) size.width else 0f, endX = if (rtl) 0f else size.width
        )
        onDrawBehind {
            val a = alpha().coerceIn(0f, 1f)
            if (a > 0f) drawRect(brush, alpha = a)
        }
    })
}

@Composable
private fun MenuCurrentPageBar(selected: Boolean) {
    Box(Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(50))
        .background(if (selected) NuvioTheme.colors.Secondary else Color.Transparent))
}

@Composable
private fun V2MainMenuRow(
    label: String,
    iconRes: Int?,
    icon: ImageVector?,
    selected: Boolean,
    focusEnabled: Boolean,
    onPicture: Boolean,
    height: Dp,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val contentColor = if (selected || isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
    MenuRowSurface(isFocused, shape, focusEnabled, onClick, height, modifier.onFocusChanged {
        isFocused = it.hasFocus
        if (it.hasFocus) onFocused()
    }) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MenuCurrentPageBar(selected)
            Spacer(Modifier.width(NuvioTheme.spacing.md))
            DrawerItemIcon(iconRes = iconRes, icon = icon, modifier = Modifier.size(18.dp), tint = contentColor)
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (selected || isFocused) FontWeight.SemiBold else FontWeight.Medium,
                    shadow = if (onPicture) MenuTextShadow else null
                )
            )
        }
    }
}

@Composable
private fun V2MainMenuProfileRow(
    name: String,
    colorHex: String,
    avatarImageUrl: String?,
    focusEnabled: Boolean,
    onPicture: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    MenuRowSurface(isFocused, RoundedCornerShape(12.dp), focusEnabled, onClick, MenuRowHeight, modifier.onFocusChanged {
        isFocused = it.hasFocus
        if (it.hasFocus) onFocused()
    }) {
        // The avatar's centre lines up with the page icons below.
        Row(Modifier.padding(start = 15.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatarCircle(name = name, colorHex = colorHex, size = 34.dp,
                avatarImageUrl = avatarImageUrl, imageCrossfade = false)
            Spacer(Modifier.width(NuvioTheme.spacing.md))
            Text(
                text = name,
                color = NuvioTheme.colors.text.onOverlay,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    textDirection = name.contentTextDirection(),
                    shadow = if (onPicture) MenuTextShadow else null
                )
            )
        }
    }
}

/** Settings sub-menu focus: a light fill and the stationary focus edge, no hardware shadow. */
@Composable
private fun MenuRowSurface(
    focused: Boolean,
    shape: RoundedCornerShape,
    focusEnabled: Boolean,
    onClick: () -> Unit,
    height: Dp,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .focusProperties { canFocus = focusEnabled }
            .nuvioV2Focus(focused, shape, hardwareShadow = false, stationary = true)
            .background(if (focused) settingsFocusFillColor() else Color.Transparent, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) { content() }
}
