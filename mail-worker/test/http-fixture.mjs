// Test-only localhost fixture. No Wrangler config, credentials or remote bindings.
import { createRequire } from 'node:module';
import { randomBytes, pbkdf2Sync } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { schema } from './synthetic-schema.mjs';

process.env.WRANGLER_LOG_PATH ??= resolve(tmpdir(),'cloud-mail-http-fixture.log');
process.env.XDG_CONFIG_HOME ??= resolve(tmpdir(),'cloud-mail-http-fixture-config');
const { readD1Migrations } = await import('@cloudflare/vitest-pool-workers/config');

// Use Wrangler's pinned build/runtime dependencies, avoiding a second toolchain.
const require = createRequire(import.meta.url);
const wranglerRequire = createRequire(require.resolve('wrangler/package.json'));
const { build } = wranglerRequire('esbuild');
const { Miniflare } = wranglerRequire('miniflare');
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const port = Number(process.env.CLOUD_MAIL_FIXTURE_PORT || 8789);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid fixture port');

const bundle = await build({
 entryPoints: [resolve(root, 'src/index.js')], bundle: true, write: false,
 format: 'esm', platform: 'browser', target: 'es2022',
});
const fixture = new Miniflare({
 modules: true, script: bundle.outputFiles[0].text, compatibilityDate: '2025-06-04',
 host: '127.0.0.1', port,
 d1Databases: {db: 'synthetic-cloud-mail'}, kvNamespaces: ['kv'], r2Buckets: ['r2'],
 bindings: {jwt_secret: randomBytes(32).toString('hex'), domain: ['example.com'], admin: 'admin@example.com', orm_log: false},
 // No network request from Worker code may leave this isolated fixture.
 outboundService: () => new Response('External network disabled in test fixture', {status:503}),
});
try {
 const db = await fixture.getD1Database('db');
 const kv = await fixture.getKVNamespace('kv');
 const r2 = await fixture.getR2Bucket('r2');
 await db.batch(schema.map(sql => db.prepare(sql)));
 const salt = randomBytes(16).toString('base64');
 const password = 'pbkdf2-sha256$100000$' + pbkdf2Sync('fixture-password', salt, 100000, 32, 'sha256').toString('base64');
 for (const id of [1,2]) {
  await db.prepare('INSERT INTO user(user_id,email,password,salt) VALUES (?,?,?,?)').bind(id,`user${id}@example.com`,password,salt).run();
  await db.prepare('INSERT INTO account(account_id,email,name,user_id) VALUES (?,?,?,?)').bind(id,`user${id}@example.com`,`User ${id}`,id).run();
 }
 await db.prepare("INSERT INTO role(role_id,name,send_type,avail_domain) VALUES (1,'fixture','count','example.com')").run();
 for (const [id,key] of [[1,'account:query'],[2,'email:send'],[3,'email:delete']]) {
  await db.prepare('INSERT INTO perm(perm_id,name,perm_key) VALUES (?,?,?)').bind(id,key,key).run();
  await db.prepare('INSERT INTO role_perm(id,role_id,perm_id) VALUES (?,1,?)').bind(id,id).run();
 }
 await kv.put('setting:',JSON.stringify({resendTokens:{},send:0,bucket:'',endpoint:'',s3AccessKey:'',s3SecretKey:'',domainList:['@example.com']}));
 for (const [id,userId,subject] of [[11,1,'Project sunrise'],[12,1,'Lunch agenda'],[21,2,'Other private subject']]) {
  await db.prepare("INSERT INTO email(email_id,user_id,account_id,send_email,name,subject,text,content,recipient,to_email,message_id,unread) VALUES (?,?,?,'sender@outside.invalid','Sender',?,'Full searchable body beyond list snippets','<p>Hello</p>',?,?,'<fixture@invalid>',0)").bind(id,userId,userId,subject,JSON.stringify([{address:`user${userId}@example.com`,name:''}]),`user${userId}@example.com`).run();
 }
 await db.prepare("INSERT INTO attachments(att_id,user_id,email_id,account_id,key,filename,mime_type,size) VALUES(1,1,11,1,'attachments/fixture.txt','fixture.txt','text/plain',18)").run();
 await r2.put('attachments/fixture.txt','Synthetic document');
 // Official parser preserves CREATE TRIGGER bodies; never split SQL on semicolons.
 for (const migration of await readD1Migrations(resolve(root,'migrations'))) {
  await db.batch(migration.queries.map(sql=>db.prepare(sql)));
 }
 const url = await fixture.ready;
 console.log(`Cloud Mail synthetic Worker ready at ${url.origin}`);
 console.log('Synthetic users: user1@example.com / user2@example.com; password: fixture-password');
 console.log('In-memory only. External network disabled. Stop and restart to reset.');
 const stop = async () => {await fixture.dispose();process.exit(0);};
 process.once('SIGINT',stop);process.once('SIGTERM',stop);
} catch (error) {
 await fixture.dispose();throw error;
}
