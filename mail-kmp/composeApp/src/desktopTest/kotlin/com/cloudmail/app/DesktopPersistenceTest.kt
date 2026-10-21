package com.cloudmail.app

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cloudmail.db.MailDatabase
import java.nio.file.Files
import kotlin.test.*

class DesktopPersistenceTest {
    @Test fun encryptedOfflineMailDraftAndQueueSurviveDatabaseReopen() {
        val directory = Files.createTempDirectory("cloudmail-offline-fixture")
        val file = directory.resolve("mail.db").toFile()
        // Reuse the recovered key, as an OS vault would, but exercise the real
        // desktop authenticated cipher and an actual on-disk SQLite database.
        val cipher = DesktopPlatform()
        val opened = mutableListOf<SqlDriver>()
        val platform = object : PlatformServices by cipher {
            override fun databaseDriver(): SqlDriver {
                val fresh = !file.exists()
                return JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").also {
                    opened += it
                    if (fresh) MailDatabase.Schema.create(it)
                }
            }
        }
        try {
            val ownerKey = "https://fixture.example#1"
            val body = "Private offline fixture body 93"
            val message = Message(7, 1, subject = "Offline fixture", text = body, html = "<p>$body</p>",
                attachments = listOf(Attachment(9, "fixture.txt", "text/plain", 8)))
            val first = MailStore(platform).apply { this.owner = ownerKey }
            first.merge(SyncPage(listOf(message), listOf(Account(1, "reader@example.com")), "persisted-cursor", false, true))
            first.saveDraft(Draft("draft", 1, to = "recipient@example.com", text = "Private unfinished fixture",
                attachments = listOf(OutgoingAttachment("draft.txt", "text/plain", "ZHJhZnQ="))))
            first.queue(PendingAction("offline-operation", 7, "read", true), message.copy(read = true))
            opened.removeAt(0).close()
            assertFalse(file.readBytes().decodeToString().contains(body))

            val restored = MailStore(platform).apply { this.owner = ownerKey }
            assertEquals("persisted-cursor", restored.cursor())
            assertEquals(body, restored.messages().single().text)
            assertEquals("<p>$body</p>", restored.messages().single().html)
            assertEquals("fixture.txt", restored.messages().single().attachments.single().name)
            assertTrue(restored.messages().single().read)
            assertEquals("offline-operation", restored.pending().single().operationId)
            assertEquals("ZHJhZnQ=", restored.drafts().single().attachments.single().contentBase64)
            restored.owner = "https://fixture.example#2"
            assertTrue(restored.messages().isEmpty()); assertTrue(restored.drafts().isEmpty()); assertNull(restored.cursor())
            restored.owner = ownerKey; restored.clear()
            opened.removeAt(0).close()
            val signedOut = MailStore(platform).apply { this.owner = ownerKey }
            assertTrue(signedOut.messages().isEmpty()); assertTrue(signedOut.pending().isEmpty()); assertTrue(signedOut.drafts().isEmpty())
        } finally {
            opened.forEach { it.close() }
            directory.toFile().deleteRecursively()
        }
    }
}
