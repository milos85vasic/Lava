package lava.tracker.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorConfigStoreTest {
    @Test
    fun `parses bundled JSON shape from spec`() {
        val json = """
        {
          "version": 1,
          "trackers": {
            "rutor": {
              "expectedHealthMarker": "RuTor",
              "mirrors": [
                {"url": "https://rutor.info", "isPrimary": true, "priority": 0, "protocol": "HTTPS"},
                {"url": "https://rutor.is", "priority": 1, "protocol": "HTTPS"}
              ]
            }
          }
        }
        """.trimIndent()
        val config = MirrorConfigStore(json).load()
        assertEquals(1, config.version)
        val rutor = config.trackers.getValue("rutor")
        assertEquals("RuTor", rutor.expectedHealthMarker)
        assertEquals(2, rutor.mirrors.size)
        assertEquals(true, rutor.mirrors[0].isPrimary)
    }
}
