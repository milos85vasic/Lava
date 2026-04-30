package lava.tracker.api

import kotlinx.serialization.Serializable

@Serializable
enum class TrackerCapability {
    SEARCH,
    BROWSE,
    FORUM,
    TOPIC,
    COMMENTS,
    FAVORITES,
    TORRENT_DOWNLOAD,
    MAGNET_LINK,
    AUTH_REQUIRED,
    CAPTCHA_LOGIN,
    RSS,
    UPLOAD,
    USER_PROFILE,
}
