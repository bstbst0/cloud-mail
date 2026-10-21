import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const config=JSON.parse(await readFile(process.argv[2],'utf8'));
assert.ok(config.name.startsWith('cloud-mail-kmp-stage-'));
assert.equal(config.url,`https://${config.name}.${config.subdomain}.workers.dev`);
const headers={'X-Cloud-Mail-Staging-Key':config.gate,'Content-Type':'application/json'};
const request=(path,options={})=>fetch(config.url+path,{headers,redirect:'error',signal:AbortSignal.timeout(60000),...options});
const health=await (await request('/__staging/health')).json();
assert.equal(health.testOnly,true);assert.equal(health.runId,config.name);
const login=await (await request('/api/app/v1/login',{method:'POST',body:JSON.stringify({email:'user2@example.com',password:config.password})})).json();
assert.equal(login.code,200);headers.Authorization=login.data.token;
try {
  for(const path of ['/api/app/v1/attachments/1','/api/app/v1/messages/11']) {
    assert.equal((await request(path)).status,404,path);
  }
  const legacy=await request('/api/oss/attachments/fixture.txt');
  // Legacy routes deliberately retain HTTP 200 + business-error envelopes.
  assert.equal((await legacy.json()).code,404);
  console.log('PASS: cross-user message, numeric attachment and legacy R2 key access denied');
} finally {
  assert.equal((await request('/api/app/v1/logout',{method:'DELETE'})).status,200);
}
