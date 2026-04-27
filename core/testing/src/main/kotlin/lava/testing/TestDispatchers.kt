package lava.testing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import lava.dispatchers.api.Dispatchers

class TestDispatchers(
    testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : Dispatchers {
    override val default: CoroutineDispatcher = testDispatcher
    override val main: CoroutineDispatcher = testDispatcher
    override val io: CoroutineDispatcher = testDispatcher
}
