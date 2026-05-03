package lava.tracker.nnmclub

import lava.tracker.api.AuthType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NnmclubDescriptorTest {

    @Test
    fun `descriptor fields are correct`() {
        assertEquals("nnmclub", NnmclubDescriptor.trackerId)
        assertEquals("NNM-Club", NnmclubDescriptor.displayName)
        assertEquals(AuthType.FORM_LOGIN, NnmclubDescriptor.authType)
        assertEquals("windows-1251", NnmclubDescriptor.encoding)
        assertTrue(NnmclubDescriptor.baseUrls.isNotEmpty())
    }
}
