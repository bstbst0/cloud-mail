package com.cloudmail.app
import kotlin.test.*
class ContractTest {
    @Test fun normalizedWireModelsAndUnknownFields() {
        val message = MailJson.decodeFromString<Message>("""{"id":123,"accountId":4,"fromAddress":"a@example.com","read":true,"starred":false,"unknown":"ignored"}""")
        assertTrue(message.read); assertEquals("inbox", message.folder)
        assertEquals(message, MailJson.decodeFromString<Message>(MailJson.encodeToString(message)))
    }
    @Test fun rawSessionContract() {
        val session = MailJson.decodeFromString<Session>("""{"token":"raw-jwt","userId":2,"email":"me@example.com","expiresAt":1000}""")
        assertEquals("raw-jwt", session.token)
    }
    @Test fun optimisticActionChangesOnlyOwnedStateField() {
        val message = Message(1, 2, subject = "Keep", starred = true)
        assertEquals(message.copy(read = true), applyAction(message, PendingAction("id", 1, "read", true)))
        assertEquals(message.copy(deleted = true), applyAction(message, PendingAction("id", 1, "delete", true)))
    }
    @Test fun serverRejectsTransportAndCredentials() {
        assertFails { ApiClient.validatedServer("http://example.com") }
        assertFails { ApiClient.validatedServer("https://") }
        assertFails { ApiClient.validatedServer("http://example.com", allowLocalHttp = true) }
        assertFails { ApiClient.validatedServer("https://user:password@example.com") }
        assertFails { ApiClient.validatedServer("https://example.com?token=x") }
        assertEquals("https://example.com", ApiClient.validatedServer("https://example.com/"))
        assertEquals("http://localhost:8787", ApiClient.validatedServer("http://localhost:8787", allowLocalHttp = true))
    }
    @Test fun attachmentByteCountAccountsForBase64Padding() {
        assertEquals(1L, OutgoingAttachment("one", "text/plain", "YQ==").size)
        assertEquals(2L, OutgoingAttachment("two", "text/plain", "YWI=").size)
        assertEquals(3L, OutgoingAttachment("three", "text/plain", "YWJj").size)
    }

}
