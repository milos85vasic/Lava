package lava.common.analytics

interface AnalyticsTracker {
    fun event(name: String, params: Map<String, String> = emptyMap())

    fun setUserId(userId: String?)

    fun setProperty(key: String, value: String?)

    fun recordNonFatal(throwable: Throwable, context: Map<String, String> = emptyMap())

    fun log(message: String)

    object Events {
        const val LOGIN_SUBMIT = "lava_login_submit"
        const val LOGIN_SUCCESS = "lava_login_success"
        const val LOGIN_FAILURE = "lava_login_failure"
        const val SEARCH_SUBMIT = "lava_search_submit"
        const val BROWSE_CATEGORY = "lava_browse_category"
        const val VIEW_TOPIC = "lava_view_topic"
        const val DOWNLOAD_TORRENT = "lava_download_torrent"
        const val PROVIDER_SELECTED = "lava_provider_selected"
        const val ENDPOINT_DISCOVERED = "lava_endpoint_discovered"
    }

    object Params {
        const val PROVIDER = "provider"
        const val QUERY = "query"
        const val CATEGORY_ID = "category_id"
        const val TOPIC_ID = "topic_id"
        const val ERROR = "error"
        const val ENDPOINT_KIND = "endpoint_kind"
    }
}
