package com.nuvio.tv.ui.navigation

/** Leaving Details switches the route at once, but its page stays on screen until the next page has drawn. */
fun isDetailPageLeaving(currentRoute: String?, visibleRoutes: List<String?>): Boolean =
    currentRoute?.startsWith("detail/") != true && visibleRoutes.any { it?.startsWith("detail/") == true }
