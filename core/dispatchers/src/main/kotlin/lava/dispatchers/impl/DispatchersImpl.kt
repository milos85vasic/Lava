package lava.dispatchers.impl

import kotlinx.coroutines.CoroutineDispatcher
import lava.dispatchers.api.Dispatchers
import javax.inject.Inject

internal class DispatchersImpl @Inject constructor() : Dispatchers {
    override val default: CoroutineDispatcher
        get() = kotlinx.coroutines.Dispatchers.Default
    override val main: CoroutineDispatcher
        get() = kotlinx.coroutines.Dispatchers.Main
    override val io: CoroutineDispatcher
        get() = kotlinx.coroutines.Dispatchers.IO
}
