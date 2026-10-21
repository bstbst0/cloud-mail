import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import { randomBytes, pbkdf2Sync } from 'node:crypto';
import assert from 'node:assert/strict';
const require = createRequire(import.meta.url);
const wranglerRequire = createRequire(require.resolve('wrangler/package.json'));
const { Miniflare } = wranglerRequire('miniflare');
const script = await readFile(process.argv[2], 'utf8');
const gate = randomBytes(32).toString('hex');
const runId = 'cloud-mail-kmp-stage-local';
const fixture = new Miniflare({
  modules: true, script, compatibilityDate: '2025-06-04',
  d1Databases: ['db'], kvNamespaces: ['kv'], r2Buckets: ['r2'],
  bindings: { STAGING_GATE: gate, STAGING_RUN_ID: runId, STAGING_EXPIRES_AT: String(Date.now()+3600000),
    jwt_secret: randomBytes(32).toString('hex'), domain: ['example.com'], admin: 'admin@staging.invalid', orm_log: false },
  outboundService: () => { throw new Error('Unexpected outbound request in local staging harness'); }
});
try {
  const request = async (path, body) => {
    const result = await fixture.dispatchFetch(`https://staging.invalid${path}`, {
      headers: { 'X-Cloud-Mail-Staging-Key': gate, 'Content-Type':'application/json' },
      method: body ? 'POST' : 'GET', body: body ? JSON.stringify(body) : undefined
    });
    assert.equal(result.status, 200, `Unexpected status for ${path}`);
    return result.json();
  };
  assert.equal((await fixture.dispatchFetch('https://staging.invalid/__staging/health')).status,403);
  assert.equal((await request('/__staging/outbound')).blocked, true);
  const salt = randomBytes(16).toString('base64');
  const passwordHash = 'pbkdf2-sha256$100000$'+pbkdf2Sync('local-synthetic-password',salt,100000,32,'sha256').toString('base64');
  assert.equal((await request('/__staging/bootstrap',{ passwordHash,salt })).messagesBeforeMigration,3);
  const first = await request('/__staging/migrate',{});
  const second = await request('/__staging/migrate',{});
  assert.equal(first.messages,3); assert.equal(first.events,3);
  assert.deepEqual(second,first);
  console.log('PASS: access gate, outbound blocking, original schema initialization, migration and replay without duplicate seed events.');
} finally { await fixture.dispose(); }
