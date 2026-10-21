// Test-only entrypoint. Never import this from src/ or a production deployment.
import worker from '../../src/index.js';
import { dbInit } from '../../src/init/init.js';
import migrations from 'staging:migrations';

const response = (value, status = 200) => Response.json(value, { status });
export default {
  async fetch(request, env, ctx) {
    if (!env.STAGING_RUN_ID?.startsWith('cloud-mail-kmp-stage-') ||
        Date.now() >= Number(env.STAGING_EXPIRES_AT) ||
        request.headers.get('X-Cloud-Mail-Staging-Key') !== env.STAGING_GATE) {
      return response({ error: 'Isolated test endpoint unavailable' }, 403);
    }
    const path = new URL(request.url).pathname;
    if (path === '/__staging/health') {
      return response({ testOnly: true, runId: env.STAGING_RUN_ID, outboundBlocked: true });
    }
    if (path === '/__staging/outbound') {
      try { await fetch('https://external-send-disabled.invalid'); }
      catch (error) { return response({ blocked: error.message === 'Staging outbound network disabled' }); }
      return response({ blocked: false }, 500);
    }
    if (request.method === 'POST' && path === '/__staging/bootstrap') {
      const existing = await env.db.prepare("SELECT count(*) AS n FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != '_cf_KV'").first();
      if (existing.n !== 0) return response({ error: 'Bootstrap requires an empty, newly created test database' }, 409);
      const { passwordHash, salt } = await request.json();
      if (!/^pbkdf2-sha256\$100000\$[A-Za-z0-9+/=]+$/.test(passwordHash ?? '') || typeof salt !== 'string') {
        return response({ error: 'Synthetic password hash required' }, 400);
      }
      const context = { env, set() {}, req: { param: () => env.jwt_secret }, text: value => new Response(value) };
      // Run the existing initialized-schema upgrade path, not a production export.
      await dbInit.init(context);
      for (const id of [1, 2]) {
        await env.db.prepare('INSERT INTO user(user_id,email,password,salt) VALUES(?,?,?,?)')
          .bind(id, `user${id}@example.com`, passwordHash, salt).run();
        await env.db.prepare('INSERT INTO account(account_id,email,name,user_id) VALUES(?,?,?,?)')
          .bind(id, `user${id}@example.com`, `Synthetic user ${id}`, id).run();
      }
      await env.db.prepare("UPDATE role SET send_type='count',send_count=100,avail_domain='example.com' WHERE role_id=1").run();
      await env.db.prepare("UPDATE setting SET send=0,resend_tokens='{}',bucket='',endpoint='',s3_access_key='',s3_secret_key=''").run();
      // Refresh the real KV-backed setting path after changing test-only settings.
      const { default: settingService } = await import('../../src/service/setting-service.js');
      await settingService.refresh(context);
      for (const [id, owner, subject] of [[11, 1, 'Project sunrise'], [12, 1, 'Lunch agenda'], [21, 2, 'Other private subject']]) {
        await env.db.prepare("INSERT INTO email(email_id,user_id,account_id,send_email,name,subject,text,content,recipient,to_email,message_id,unread) VALUES(?,?,?,'sender@outside.invalid','Synthetic sender',?,'Full searchable body beyond list snippets','<p>Hello</p>',?,?,'<fixture@invalid>',0)")
          .bind(id, owner, owner, subject, JSON.stringify([{ address: `user${owner}@example.com`, name: '' }]), `user${owner}@example.com`).run();
      }
      await env.db.prepare("INSERT INTO attachments(att_id,user_id,email_id,account_id,key,filename,mime_type,size) VALUES(1,1,11,1,'attachments/fixture.txt','fixture.txt','text/plain',18)").run();
      await env.r2.put('attachments/fixture.txt', 'Synthetic document');
      return response({ initialized: true, syntheticUsers: 2, messagesBeforeMigration: 3 });
    }
    if (request.method === 'POST' && path === '/__staging/migrate') {
      for (const migration of migrations) await env.db.batch(migration.queries.map(sql => env.db.prepare(sql)));
      const events = await env.db.prepare('SELECT count(*) AS n FROM app_change_log').first();
      const messages = await env.db.prepare('SELECT count(*) AS n FROM email').first();
      return response({ applied: true, events: events.n, messages: messages.n });
    }
    if (path.startsWith('/__staging/')) return response({ error: 'Unknown test operation' }, 404);
    if (!path.startsWith('/api/')) return response({ error: 'API-only isolated deployment' }, 404);
    // Original Worker, original auth and API routes; only bindings are isolated.
    return worker.fetch(request, env, ctx);
  }
};
