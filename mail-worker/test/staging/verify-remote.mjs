import { readFile } from 'node:fs/promises';
import { randomBytes, pbkdf2Sync } from 'node:crypto';
import assert from 'node:assert/strict';

const config = JSON.parse(await readFile(process.argv[2], 'utf8'));
const url = new URL(config.url);
assert.ok(config.name.startsWith('cloud-mail-kmp-stage-'));
assert.equal(url.protocol, 'https:');
assert.equal(url.hostname, `${config.name}.${config.subdomain}.workers.dev`);
assert.equal(url.pathname, '/');
assert.ok(!url.username && !url.password && !url.port && !url.search && !url.hash);
const request = async (path, body) => {
  const result = await fetch(new URL(path, url), {
    redirect: 'error', signal: AbortSignal.timeout(60000),
    headers: { 'X-Cloud-Mail-Staging-Key': config.gate, 'Content-Type': 'application/json' },
    method: body ? 'POST' : 'GET', body: body ? JSON.stringify(body) : undefined
  });
  assert.equal(result.status, 200, `Unexpected status for ${path}`);
  return result.json();
};
assert.equal((await fetch(new URL('/__staging/health', url), { redirect:'error' })).status, 403);
const health = await request('/__staging/health');
assert.equal(health.testOnly, true);
assert.equal(health.runId, config.name);
assert.equal((await request('/__staging/outbound')).blocked, true);
console.log('PASS: remote staging identity, access gate and outbound network blockade');
const salt = randomBytes(16).toString('base64');
const passwordHash = 'pbkdf2-sha256$100000$' + pbkdf2Sync(config.password, salt, 100000, 32, 'sha256').toString('base64');
assert.equal((await request('/__staging/bootstrap', { passwordHash, salt })).messagesBeforeMigration, 3);
console.log('PASS: original schema initialization and synthetic fixtures on isolated Cloudflare D1/KV/R2');
const first = await request('/__staging/migrate', {});
const second = await request('/__staging/migrate', {});
assert.equal(first.messages, 3);
assert.equal(first.events, 3);
assert.deepEqual(second, first);
console.log('PASS: actual app migration and repeat migration without duplicate seed events');
