import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const config = JSON.parse(await readFile(process.argv[2], 'utf8'));
assert.ok(config.name.startsWith('cloud-mail-kmp-stage-'));
assert.equal(config.url, `https://${config.name}.${config.subdomain}.workers.dev`);
const request = async (path, token, method = 'GET', body) => {
  const response = await fetch(config.url + path, {
    method, redirect: 'error', signal: AbortSignal.timeout(60000),
    headers: { 'X-Cloud-Mail-Staging-Key':config.gate, 'Content-Type':'application/json', ...(token ? { Authorization:token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return { status:response.status, body: await response.json() };
};
const health = await request('/__staging/health');
assert.equal(health.body.runId,config.name); assert.equal(health.body.testOnly,true);
const login = await request('/api/login',null,'POST',{email:'user1@example.com',password:config.password});
assert.equal(login.body.code,200);
const legacy = login.body.data.token;
const appLogin = await request('/api/app/v1/login',null,'POST',{email:'user1@example.com',password:config.password});
assert.equal(appLogin.status,200);
const app = appLogin.body.data.token;
try {
  const before = await request('/api/app/v1/sync',app);
  assert.equal(before.body.code,200);
  assert.equal((await request('/api/email/read',legacy,'PUT',{emailIds:[12]})).body.code,200);
  assert.equal((await request('/api/star/add',legacy,'POST',{emailId:12})).body.code,200);
  const changed = await request('/api/app/v1/sync?cursor='+encodeURIComponent(before.body.data.cursor),app);
  assert.equal(changed.body.code,200);
  assert.ok(changed.body.data.messages.some(m=>m.id===12 && m.read && m.starred));
  assert.equal((await request('/api/star/cancel?emailId=12',legacy,'DELETE')).body.code,200);
  assert.equal((await request('/api/email/delete?emailIds=12',legacy,'DELETE')).body.code,200);
  const deleted = await request('/api/app/v1/sync?cursor='+encodeURIComponent(changed.body.data.cursor),app);
  assert.ok(deleted.body.data.messages.some(m=>m.id===12 && m.deleted));
  console.log('PASS: legacy Web login, read/star/cancel/delete and incremental native sync/tombstone compatibility');
  for (const path of ['/api/test','/api/login-extra','/api/app/v1/messages/11']) {
    const result=await request(path);
    assert.ok(result.status===401 || result.body.code===401,`Unauthorized route: ${path}`);
  }
  console.log('PASS: unauthenticated protected/test/prefix routes rejected');
} finally {
  assert.equal((await request('/api/logout',legacy,'DELETE')).body.code,200);
  assert.equal((await request('/api/app/v1/logout',app,'DELETE')).body.code,200);
}
assert.equal((await request('/api/app/v1/accounts',app)).status,401);
assert.equal((await request('/api/my/loginUserInfo',legacy)).body.code,401);
console.log('PASS: independent legacy/app session revocation');
