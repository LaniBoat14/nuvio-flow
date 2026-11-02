package com.nuvio.tv.domain.model

enum class AppIconOption(val key: String, val entitlement: CosmeticEntitlement? = null) {
    ORIGINAL("original"),
    ARCTIC_BLUE("arctic_blue", CosmeticEntitlement.ARCTIC_BLUE_THEME),
    EMERALD("emerald"),
    ROSE_GOLD("rose_gold", CosmeticEntitlement.ROSE_GOLD_THEME),
    COPPER("copper"),
    GRAPHITE("graphite", CosmeticEntitlement.GRAPHITE_THEME),
    GOLD("gold", CosmeticEntitlement.GOLD_THEME),
    JADE("jade", CosmeticEntitlement.JADE_THEME),
    GLASS("glass"),
    CRIMSON("crimson"),
    OCEAN("ocean"),
    VIOLET("violet"),
    EMERALD_GREEN("emerald_green"),
    AMBER("amber"),
    ROSE("rose"),
    WHITE("white")
}

enum class AppIconLook {
    CLASSIC,
    CARD,
    CARD_LETTERING
}

/** One launcher entry: a colour in a look. Only the card looks come with a transparent background. */
data class AppIconChoice(
    val colour: AppIconOption,
    val look: AppIconLook = AppIconLook.CLASSIC,
    val transparent: Boolean = false
) {
    init {
        require(!transparent || look != AppIconLook.CLASSIC) { "Classic icons have no transparent banner" }
    }

    companion object {
        fun of(colour: AppIconOption, look: AppIconLook, transparent: Boolean) =
            AppIconChoice(colour, look, transparent && look != AppIconLook.CLASSIC)

        val all: List<AppIconChoice> = AppIconLook.entries.flatMap { look ->
            val backgrounds = if (look == AppIconLook.CLASSIC) listOf(false) else listOf(false, true)
            backgrounds.flatMap { transparent -> AppIconOption.entries.map { AppIconChoice(it, look, transparent) } }
        }
    }
}

/**
 * Supporter colours follow the matching supporter theme, in every look. The icon already in use stays listed, but
 * not its other looks.
 */
fun canUseAppIcon(entitlements: CosmeticEntitlements, choice: AppIconChoice, selected: AppIconChoice): Boolean {
    val entitlement = choice.colour.entitlement
    return entitlement == null || entitlements.includes(entitlement) || choice == selected
}
