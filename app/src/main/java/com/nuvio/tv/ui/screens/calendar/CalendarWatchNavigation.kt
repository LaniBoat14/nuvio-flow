package com.nuvio.tv.ui.screens.calendar

import androidx.navigation.NavController
import com.nuvio.tv.core.calendar.CalendarWatchTarget
import com.nuvio.tv.ui.navigation.Screen

/** Straight to the streams when the episode is known, else the show's page on that episode. */
fun NavController.openCalendarWatchTarget(target: CalendarWatchTarget) {
    val videoId = target.videoId
    if (videoId != null) {
        navigate(
            Screen.Stream.createRoute(
                videoId = videoId,
                contentType = target.contentType,
                title = target.name,
                poster = target.poster,
                backdrop = target.backdrop,
                logo = target.logo,
                season = target.season,
                episode = target.episode,
                episodeName = target.episodeTitle,
                contentId = target.contentId,
                contentName = target.name,
                returnToDetailOnBack = target.contentType.equals("series", ignoreCase = true),
                returnToHomeOnBack = true
            )
        )
    } else {
        navigate(
            Screen.Detail.createRoute(
                itemId = target.contentId,
                itemType = target.contentType,
                returnFocusSeason = target.season,
                returnFocusEpisode = target.episode
            )
        )
    }
}
