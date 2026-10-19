# Cloud Mail native API v1

Base: HTTPS server origin + `/api/app/v1`. Authorization is the **raw JWT** (no Bearer prefix). JSON success: `{ "code":200,"message":"success","data":... }`. App errors use matching HTTP status plus envelope code; legacy routes preserve their HTTP/envelope behavior. No real mail is sent by fixtures.

## Models

```kotlin
Account(id: Long, email: String, name: String)
Attachment(id: Long, name: String, mimeType: String, size: Long)
InlineAttachment(id: Long, name: String, mimeType: String, size: Long, reference: String)
Message(id: Long, accountId: Long, fromAddress: String, fromName: String,
 subject: String, text: String, html: String, to: List<String>, cc: List<String>,
 bcc: List<String>, receivedAt: String, folder: String, read: Boolean,
 starred: Boolean, deleted: Boolean, status: Int, attachments: List<Attachment>,
 inlineAttachments: List<InlineAttachment> = emptyList())
SyncPage(messages: List<Message>, accounts: List<Account>, cursor: String,
 hasMore: Boolean, reset: Boolean)
```

All properties present, non-null, arrays default empty. `inlineAttachments` metadata exposes no object keys; reference is `cid:cloud-mail-ID`. Known authorized inline IMG storage/CID sources in native html are rewritten to that reference; external sources are preserved and clients block tracking by default. Legacy stored/Web HTML is unchanged. Native detail can hydrate numeric authorized image attachments into safe data URLs with byte/signature checks. `folder` is `inbox` or `sent`. `receivedAt` is UTC ISO-8601. `status` retains existing Worker enum (0 received, 1 sent, 2 delivered, 3 bounced, 4 complained, 5 delayed, 6 saving, 7 no recipient, 8 failed). Deleted/purged/unowned messages are tombstones: id plus `deleted=true`, other properties safe defaults. Clients remove cached bodies/attachments for tombstones. BCC only returned for sender's sent copy, never received copy. Attachments carry numeric IDs, never storage keys.

## Routes

| Method / path | Input | Envelope data |
|---|---|---|
| POST `/login` | `{email,password}` | `{token,userId,email,expiresAt}` (expiresAt Unix seconds) |
| DELETE `/logout` | none | null; revokes current session |
| GET `/accounts` | none | `List<Account>` (complete replacement) |
| GET `/sync` | `cursor?`, `limit?` (1–50 requested; internal ceiling/default10) | `SyncPage` |
| GET `/messages` | `q?`, `folder?` inbox/sent/starred, `accountId?`, `read?` true/false, `hasAttachment?` true/false, `after?` ISO date, `before?` ISO date, `next?`, `limit?` | `{messages,next:String?}` |
| GET `/messages/:id` | none | `Message` |
| POST `/actions` | `{operationId,messageId,kind,value}` | `{operationId,message:Message}` |
| POST `/send` | see below | `{operationId,status,messageId:Long?}` |
| GET `/send/:operationId` | none | same send status |
| GET `/attachments/:id` | none | binary, authenticated owner check, safe attachment disposition |

`operationId` is a random UUID (8–128 ASCII letters/digits/_/- accepted). Actions kind `read`, `star`, `delete`; explicit boolean value required. `delete=false` restores soft-deleted mail (Undo). Reusing ID with a different action/send payload returns 409. Actions are transactionally idempotent; replay returns current authoritative message. Non-idempotent legacy send is never auto-retried.

Send JSON: `{operationId,accountId,to:[String],cc:[String],bcc:[String],subject,text,html,replyToId:Long?,attachments:[{name,mimeType,contentBase64}]}`. At least one To address; ≤50 total recipients, ≤10 attachments, ≤20 MiB aggregate decoded attachments, ≤1 MiB body, safe filenames; Worker request cap 30 MiB. HTML supplied by composer must escape plain text. Existing sender account and reply mail must belong to caller. Resend keys remain only in Worker. `status` is `sent`, `pending`, `uncertain`, or `failed`. A durable D1 operation claims the request before sending. Resend receives a user-scoped idempotency key. Repeated POST never invokes provider twice; it returns persisted status. A timed-out/interrupted pending operation is reported uncertain after five minutes. Never automatically resubmit uncertain sends with a new ID. Keep local draft until status sent. Failed/uncertain operation cannot be restarted by same ID; user must review before creating a new operation.

## Sync semantics

First request without cursor returns `reset=true`, seeds client replacement and begins paged event replay. Cursor is signed, user-bound, epoch-bound opaque v1 token. Store page messages/accounts **and cursor atomically**. `accounts` on each page is the current complete list; remove data for absent accounts. Each page contains ≤10 event-derived authoritative message DTOs (deduplicated by remote ID). `hasMore=true` means call same endpoint with returned cursor until false. Each cycle has a fixed sequence watermark, so continuous incoming mail cannot prevent completion. New changes after watermark are fetched next cycle. Soft/hard deletion, transfers and attachments produce events, including legacy Web and email intake writes via SQLite triggers in the same transaction. State is server authoritative; no wall-clock last-write-wins. Empty pages still advance cursor. Duplicate IDs across pages are upserts, never duplicate messages.

Invalid signature, user/epoch mismatch, cursor ahead of log, or future pruning triggers a safe initial replay with `reset=true`. Client pending operations must survive reset. Log is append-only in v1; no retention/pruning until a reviewed minimum-cursor protocol exists. Existing records are seeded exactly once in migration. No second server email copy/database is created.

## Compatibility and deployment

New routes require migration `mail-worker/migrations/0001_app_v1.sql` after existing database initialization. They do not run DDL in HTTP requests. Existing routes, data and legacy JWT sessions retain their schemas and authentication behavior; new app JWTs have 30-day fixed expiry and independently revocable D1 sessions. App session revocation is strongly read from D1 rather than relying on eventually consistent KV. Existing role permissions gate account query, send and delete.

Private attachments require authenticated routes, including existing `/api/oss/*` and `/attachments/*`. Web needs authorized Blob fetch for those objects. Public custom R2/S3 domains must be disabled for attachments by operators before release. Public static background assets remain public. No production migration/deployment is performed during development.

Capacity: v1 internally caps sync/search pages at10 even if request limit50; limit is an upper bound, follow pagination. This reduces peak memory for complete text+HTML without truncating content. Legacy pathological large bodies still require monitoring against D1 row/value limits (2MiB) and Worker memory budget.
