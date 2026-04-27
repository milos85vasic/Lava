package lava.connection

internal sealed interface ConnectionsSideEffect {
    data object ShowConnectionDialog : ConnectionsSideEffect
}
