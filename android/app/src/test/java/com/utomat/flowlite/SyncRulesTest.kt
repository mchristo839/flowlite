package com.utomat.flowlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRulesTest {
    @Test fun lastWriteWins() {
        assertTrue("missing locally", SyncRules.remoteWins(null, 5))
        assertTrue("remote newer", SyncRules.remoteWins(4, 5))
        assertFalse("local newer", SyncRules.remoteWins(6, 5))
        assertFalse("tie keeps local", SyncRules.remoteWins(5, 5))
    }

    @Test fun deterministicIdsMatchPython() {
        // python3 -c "import uuid;print(uuid.uuid5(uuid.NAMESPACE_URL,'flowlite:dictionary:kubernetes'))"
        assertEquals("55ac65ef-9da4-55c6-a4ce-1df859da6111", SyncRules.dictionaryId("  Kubernetes "))
    }

    @Test fun pullCursorOverlapsTwoSeconds() {
        assertEquals("", SyncRules.pullFrom(""))
        assertEquals("2026-10-12 10:33:09.123Z", SyncRules.pullFrom("2026-10-12 10:33:11.123Z"))
        assertEquals("2026-10-12 10:33:11.200Z", SyncRules.maxCursor("2026-10-12 10:33:11.123Z", "2026-10-12 10:33:11.200Z"))
    }

    @Test fun settingsJsonRoundTrip() {
        assertEquals("\"el\"", SyncRules.toJson(SyncRules.parseJson("\"el\"")))
        assertEquals("true", SyncRules.toJson(SyncRules.parseJson("true")))
    }
}
