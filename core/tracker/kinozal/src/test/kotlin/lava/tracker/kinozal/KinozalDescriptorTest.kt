package lava.tracker.kinozal

import org.junit.Assert.assertEquals
import org.junit.Test

class KinozalDescriptorTest {
    @Test
    fun `descriptor has expected id and capabilities`() {
        assertEquals("kinozal", KinozalDescriptor.trackerId)
        assertEquals("Kinozal.tv", KinozalDescriptor.displayName)
        assertEquals("windows-1251", KinozalDescriptor.encoding)
    }
}
