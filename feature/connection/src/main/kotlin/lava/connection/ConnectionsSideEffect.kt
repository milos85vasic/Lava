package lava.connection

internal sealed interface ConnectionsSideEffect {
    data object ShowConnectionDialog : ConnectionsSideEffect
    data class ShowMessage(val message: String) : ConnectionsSideEffect
}
