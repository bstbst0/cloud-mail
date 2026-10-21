-- Apply only after existing Cloud Mail initialization. Additive and idempotent.
-- SQLite triggers execute atomically with legacy writes and Email Routing intake.
CREATE TABLE IF NOT EXISTS app_sync_meta (id INTEGER PRIMARY KEY CHECK(id=1), epoch TEXT NOT NULL);
INSERT OR IGNORE INTO app_sync_meta VALUES (1, lower(hex(randomblob(16))));
CREATE TABLE IF NOT EXISTS app_change_log (seq INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, message_id INTEGER NOT NULL DEFAULT 0);
CREATE INDEX IF NOT EXISTS app_change_user_seq ON app_change_log(user_id,seq);
CREATE TABLE IF NOT EXISTS app_session (token TEXT PRIMARY KEY, user_id INTEGER NOT NULL, expires_at INTEGER NOT NULL, device TEXT NOT NULL DEFAULT 'Native app', created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS app_session_user ON app_session(user_id);
CREATE TABLE IF NOT EXISTS app_action (user_id INTEGER NOT NULL, operation_id TEXT NOT NULL, fingerprint TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(user_id,operation_id));
CREATE TABLE IF NOT EXISTS app_send_operation (user_id INTEGER NOT NULL, operation_id TEXT NOT NULL, fingerprint TEXT NOT NULL, status TEXT NOT NULL, message_id INTEGER, created_at INTEGER NOT NULL, PRIMARY KEY(user_id,operation_id));
CREATE TABLE IF NOT EXISTS app_rate_limit (bucket TEXT PRIMARY KEY, attempts INTEGER NOT NULL, expires_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS app_email_user_id ON email(user_id,email_id);
CREATE INDEX IF NOT EXISTS app_email_user_folder ON email(user_id,is_del,type,email_id);
CREATE INDEX IF NOT EXISTS app_attachment_owner ON attachments(user_id,email_id,att_id);
CREATE INDEX IF NOT EXISTS app_attachment_key ON attachments(key,user_id);
CREATE INDEX IF NOT EXISTS app_star_owner ON star(user_id,email_id);
-- Seed once: rerunning does not add duplicate initialization events.
INSERT INTO app_change_log(user_id,message_id) SELECT user_id,email_id FROM email WHERE NOT EXISTS (SELECT 1 FROM app_change_log);
CREATE TRIGGER IF NOT EXISTS app_email_insert AFTER INSERT ON email BEGIN
 INSERT INTO app_change_log(user_id,message_id) VALUES(NEW.user_id,NEW.email_id);
END;
CREATE TRIGGER IF NOT EXISTS app_email_update AFTER UPDATE ON email BEGIN
 INSERT INTO app_change_log(user_id,message_id) VALUES(NEW.user_id,NEW.email_id);
 INSERT INTO app_change_log(user_id,message_id) SELECT OLD.user_id,OLD.email_id WHERE OLD.user_id != NEW.user_id;
END;
CREATE TRIGGER IF NOT EXISTS app_email_delete AFTER DELETE ON email BEGIN
 INSERT INTO app_change_log(user_id,message_id) VALUES(OLD.user_id,OLD.email_id);
END;
CREATE TRIGGER IF NOT EXISTS app_star_insert AFTER INSERT ON star BEGIN
 INSERT INTO app_change_log(user_id,message_id) VALUES(NEW.user_id,NEW.email_id);
END;
CREATE TRIGGER IF NOT EXISTS app_star_delete AFTER DELETE ON star BEGIN
 INSERT INTO app_change_log(user_id,message_id) VALUES(OLD.user_id,OLD.email_id);
END;
CREATE TRIGGER IF NOT EXISTS app_star_update AFTER UPDATE ON star BEGIN
 INSERT INTO app_change_log(user_id,message_id) VALUES(OLD.user_id,OLD.email_id);
 INSERT INTO app_change_log(user_id,message_id) VALUES(NEW.user_id,NEW.email_id);
END;
CREATE TRIGGER IF NOT EXISTS app_attachment_insert AFTER INSERT ON attachments BEGIN
 INSERT INTO app_change_log(user_id,message_id) VALUES(NEW.user_id,NEW.email_id);
END;
CREATE TRIGGER IF NOT EXISTS app_attachment_update AFTER UPDATE ON attachments BEGIN
 INSERT INTO app_change_log(user_id,message_id) VALUES(OLD.user_id,OLD.email_id);
 INSERT INTO app_change_log(user_id,message_id) VALUES(NEW.user_id,NEW.email_id);
END;
CREATE TRIGGER IF NOT EXISTS app_attachment_delete AFTER DELETE ON attachments BEGIN
 INSERT INTO app_change_log(user_id,message_id) VALUES(OLD.user_id,OLD.email_id);
END;
CREATE TRIGGER IF NOT EXISTS app_account_insert AFTER INSERT ON account BEGIN
 INSERT INTO app_change_log(user_id,message_id) VALUES(NEW.user_id,0);
END;
CREATE TRIGGER IF NOT EXISTS app_account_update AFTER UPDATE ON account BEGIN
 INSERT INTO app_change_log(user_id,message_id) VALUES(NEW.user_id,0);
 INSERT INTO app_change_log(user_id,message_id) SELECT OLD.user_id,0 WHERE OLD.user_id != NEW.user_id;
 INSERT INTO app_change_log(user_id,message_id) SELECT e.user_id,e.email_id FROM email e WHERE e.account_id=OLD.account_id AND (OLD.user_id!=NEW.user_id OR OLD.is_del!=NEW.is_del);
END;
CREATE TRIGGER IF NOT EXISTS app_account_delete AFTER DELETE ON account BEGIN
 INSERT INTO app_change_log(user_id,message_id) VALUES(OLD.user_id,0);
 INSERT INTO app_change_log(user_id,message_id) SELECT user_id,email_id FROM email WHERE account_id=OLD.account_id;
END;
CREATE TRIGGER IF NOT EXISTS app_user_revoke AFTER UPDATE OF password,salt,status,is_del ON user
WHEN OLD.password!=NEW.password OR OLD.salt!=NEW.salt OR NEW.status!=0 OR NEW.is_del!=0 BEGIN
 DELETE FROM app_session WHERE user_id=NEW.user_id;
END;
CREATE TRIGGER IF NOT EXISTS app_user_delete AFTER DELETE ON user BEGIN
 DELETE FROM app_session WHERE user_id=OLD.user_id;
END;
