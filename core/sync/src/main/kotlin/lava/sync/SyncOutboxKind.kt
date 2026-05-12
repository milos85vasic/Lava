package lava.sync

enum class SyncOutboxKind(val wire: String) {
    CREDENTIALS("credentials"),
    BINDING("binding"),
    SYNC_TOGGLE("sync_toggle"),
    CLONED_PROVIDER("cloned_provider"),
    USER_MIRROR("user_mirror"),
}
