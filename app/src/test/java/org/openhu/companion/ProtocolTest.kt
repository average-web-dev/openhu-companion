package org.openhu.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun helloAnnouncesTheApp() {
        val hello = Protocol.hello("1.0.0", "OpenHU", listOf("extensions"), listOf("Music"))
        assertEquals("hello", hello.getString("type"))
        assertEquals("org.openhu.companion", hello.getString("app"))
        assertEquals(1, hello.getInt("protocol"))
        assertEquals("OpenHU", hello.getString("label"))
        assertEquals("extensions", hello.getJSONArray("features").getString(0))
        assertEquals("Music", hello.getJSONArray("mediaLabels").getString(0))
    }

    @Test
    fun emitIsABusEmitRequest() {
        val message = Protocol.emit(3, "music.command", JSONObject().put("cmd", "next"))
        assertEquals("bus.emit", message.getString("method"))
        assertEquals("music.command", message.getJSONObject("params").getString("topic"))
    }

    @Test
    fun parsesEventsAndResponses() {
        val event = Protocol.parse("""{"type":"event","topic":"media.current","data":{"source":"music"}}""")
        assertTrue(event is Protocol.Message.Event)
        assertEquals("music", (event as Protocol.Message.Event).data.getString("source"))
        val response = Protocol.parse("""{"type":"response","id":4,"result":{"a":1}}""") as Protocol.Message.Response
        assertEquals(4, response.id)
        assertNull(response.error)
        assertNull(Protocol.parse("not json"))
    }
}
