package com.nuvio.tv.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import com.nuvio.tv.domain.model.AppIconChoice
import com.nuvio.tv.domain.model.AppIconLook

/**
 * Launchers that read icon packs get our transparent banners by name. Classic entries share the Card lettering
 * banner, which has the same layout: mark above the wordmark.
 */
internal fun iconPackDrawableName(choice: AppIconChoice): String {
    val look = if (choice.look == AppIconLook.CARD) "card" else "lettering"
    return "arc_${look}_clear_${choice.colour.key}"
}

@SuppressLint("DiscouragedApi")
internal fun iconPackDrawable(context: Context, choice: AppIconChoice): Int =
    context.resources.getIdentifier(iconPackDrawableName(choice), "drawable", context.packageName)

/** The launcher entry Android has on, or null when that cannot be read. */
internal fun activeAppIcon(context: Context): AppIconChoice? {
    val intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setPackage(context.packageName)
    val found = runCatching { context.packageManager.queryIntentActivities(intent, 0) }
        .getOrNull() ?: return null
    val names = found.mapNotNull { it.activityInfo?.name }.toSet()
    return AppIconChoice.all.firstOrNull { launcherClassName(it) in names }
}

internal val iconPackLooks = listOf(AppIconLook.CARD, AppIconLook.CARD_LETTERING)
