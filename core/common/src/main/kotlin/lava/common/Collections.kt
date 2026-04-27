package lava.common

inline fun <reified T, reified R : T, Q> List<T>.mapInstanceOf(transform: (R) -> Q): List<Q> {
    return filterIsInstance<R>().map(transform)
}
