package com.nuvio.tv.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import com.nuvio.tv.domain.model.AppIconChoice
import com.nuvio.tv.domain.model.AppIconLook
import com.nuvio.tv.domain.model.AppIconOption
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AppIconSettingsState(
    val selected: AppIconChoice = AppIconChoice(AppIconOption.ORIGINAL),
    val pending: AppIconChoice? = null,
    val changeFailed: Boolean = false
)

@Singleton
class AppIconManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val launcherClasses: Map<AppIconChoice, String> = AppIconChoice.all.associateWith(::launcherClassName)
    private val choicesByClass: Map<String, AppIconChoice> = launcherClasses.entries.associate { (k, v) -> v to k }

    private val _state = MutableStateFlow(AppIconSettingsState())
    val state: StateFlow<AppIconSettingsState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("app_icon", Context.MODE_PRIVATE)

    init {
        val enabled = enabledEntries()
        val selected = when {
            enabled == null -> AppIconChoice(AppIconOption.ORIGINAL)
            enabled.isEmpty() -> {
                restoreDefault()
                AppIconChoice(AppIconOption.ORIGINAL)
            }
            enabled.size == 1 -> enabled.first()
            else -> keepOne(enabled)
        }
        _state.value = AppIconSettingsState(selected = selected)
    }

    fun select(choice: AppIconChoice): Boolean {
        val current = _state.value
        if (current.pending != null || current.selected == choice) return false

        _state.value = current.copy(pending = choice, changeFailed = false)
        savePick(choice)
        val changed = activate(choice)
        _state.value = if (changed) {
            AppIconSettingsState(selected = choice)
        } else {
            savePick(current.selected)
            current.copy(changeFailed = true)
        }
        return changed
    }

    /** The launcher entry that is switched on; a change here means the home screen icon changed. */
    fun selectedEntry(): String = launcherClasses.getValue(_state.value.selected)

    /** The icon of that entry, as the home screen draws it. */
    fun selectedIcon(): Drawable? = runCatching {
        context.packageManager.getActivityIcon(component(selectedEntry()))
    }.getOrNull()

    fun clearFailure() {
        val current = _state.value
        if (!current.changeFailed) return
        _state.value = current.copy(changeFailed = false)
    }

    /** The launcher entries Android has switched on, in one query instead of one check per entry. */
    private fun enabledEntries(): List<AppIconChoice>? {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val found = runCatching { context.packageManager.queryIntentActivities(intent, 0) }.getOrNull() ?: return null
        return found.mapNotNull { choicesByClass[it.activityInfo?.name] }
    }

    private fun savePick(choice: AppIconChoice) {
        prefs.edit().putString(KeyLauncher, launcherClasses.getValue(choice)).commit()
    }

    /** A switch cut short on older Android can leave two entries on: keep the one the user picked, switch off the rest. */
    private fun keepOne(enabled: List<AppIconChoice>): AppIconChoice {
        val picked = prefs.getString(KeyLauncher, null)?.let(choicesByClass::get)
        val keep = enabled.firstOrNull { it == picked }
            ?: enabled.firstOrNull { it != AppIconChoice(AppIconOption.ORIGINAL) }
            ?: enabled.first()
        enabled.filter { it != keep }.forEach { choice ->
            runCatching {
                context.packageManager.setComponentEnabledSetting(
                    component(launcherClasses.getValue(choice)),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
        return keep
    }

    private fun restoreDefault() {
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                component(launcherClasses.getValue(AppIconChoice(AppIconOption.ORIGINAL))),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    /** Switches on the new entry first, then off the ones that were on, so the app never leaves the home screen. */
    private fun activate(choice: AppIconChoice): Boolean {
        val selectedClass = launcherClasses.getValue(choice)
        val packageManager = context.packageManager
        val enabled = enabledEntries()
        val toDisable = if (enabled == null) {
            launcherClasses.values.filter { it != selectedClass }
        } else {
            (enabled + _state.value.selected).map(launcherClasses::getValue).filter { it != selectedClass }.distinct()
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.setComponentEnabledSettings(
                    listOf(
                        PackageManager.ComponentEnabledSetting(
                            component(selectedClass),
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    ) + toDisable.map { className ->
                        PackageManager.ComponentEnabledSetting(
                            component(className),
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    }
                )
            } else {
                packageManager.setComponentEnabledSetting(
                    component(selectedClass),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                toDisable.forEach { className ->
                    packageManager.setComponentEnabledSetting(
                        component(className),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
        }.isSuccess
    }

    private fun component(className: String): ComponentName =
        ComponentName(context.packageName, className)
}

private const val KeyLauncher = "launcher"

internal fun launcherClassName(choice: AppIconChoice): String {
    val colour = when (choice.colour) {
        AppIconOption.ORIGINAL -> if (choice.look == AppIconLook.CLASSIC) "Default" else "Original"
        AppIconOption.ARCTIC_BLUE -> "ArcticBlue"
        AppIconOption.EMERALD -> "Emerald"
        AppIconOption.ROSE_GOLD -> "RoseGold"
        AppIconOption.COPPER -> "Copper"
        AppIconOption.GRAPHITE -> "Graphite"
        AppIconOption.GOLD -> "Gold"
        AppIconOption.JADE -> "Jade"
        AppIconOption.GLASS -> "Glass"
        AppIconOption.CRIMSON -> "Crimson"
        AppIconOption.OCEAN -> "Ocean"
        AppIconOption.VIOLET -> "Violet"
        AppIconOption.EMERALD_GREEN -> "EmeraldGreen"
        AppIconOption.AMBER -> "Amber"
        AppIconOption.ROSE -> "Rose"
        AppIconOption.WHITE -> "White"
    }
    val look = when (choice.look) {
        AppIconLook.CLASSIC -> ""
        AppIconLook.CARD -> "Card"
        AppIconLook.CARD_LETTERING -> "Lettering"
    }
    val background = if (choice.transparent) "Clear" else ""
    return "com.nuvio.tv.launcher.AppIcon$look$background$colour"
}
