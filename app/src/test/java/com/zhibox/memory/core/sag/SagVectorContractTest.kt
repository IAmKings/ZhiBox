package com.zhibox.memory.core.sag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** B8：SAG role/空间指纹契约（纯 JVM，防 B9/B10 退化为自由字符串）。 */
class SagVectorContractTest {

    @Test
    fun roleWireRoundTripAndUnknownRejected() {
        assertEquals(SagVectorRole.EVENT_CONTENT, SagVectorRole.fromWire("event_content"))
        assertEquals(SagVectorRole.ENTITY_DESCRIPTION, SagVectorRole.fromWire("entity_description"))
        assertNull(SagVectorRole.fromWire("event_title"))
        assertNull(SagVectorRole.fromWire(""))
        assertEquals(listOf("event_content", "entity_description"), SagVectorRole.WIRES)
    }

    @Test
    fun spaceIdDerivationAndMismatchRejected() {
        val base = "mediapipe-universal/embeddinggemma-2/raw-v1"
        val eventSpace = SagVectorSpace.spaceIdFor(base, SagVectorRole.EVENT_CONTENT)
        val entitySpace = SagVectorSpace.spaceIdFor(base, SagVectorRole.ENTITY_DESCRIPTION)
        assertEquals("$base#role=event_content", eventSpace)
        assertEquals("$base#role=entity_description", entitySpace)
        SagVectorSpace.requireMatches(eventSpace, base, SagVectorRole.EVENT_CONTENT)
        SagVectorSpace.requireMatches(entitySpace, base, SagVectorRole.ENTITY_DESCRIPTION)
        assertThrows(IllegalArgumentException::class.java) {
            SagVectorSpace.requireMatches(eventSpace, base, SagVectorRole.ENTITY_DESCRIPTION)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SagVectorSpace.requireMatches("some-chunk-space", base, SagVectorRole.EVENT_CONTENT)
        }
    }
}
