import { env, createExecutionContext, waitOnExecutionContext, applyD1Migrations } from 'cloudflare:test';
import worker from '../src/index';
import cryptoUtils from '../src/utils/crypto-utils';
import jwtUtils from '../src/utils/jwt-utils';

import { schema } from './synthetic-schema.mjs';

export async function setup() {
 await env.db.batch(schema.map(sql => env.db.prepare(sql)));
 const password = await cryptoUtils.hashPassword('fixture-password');
 for (const id of [1,2]) {
  await env.db.prepare('INSERT INTO user(user_id,email,password,salt) VALUES (?,?,?,?)').bind(id, `user${id}@example.com`,password.hash,password.salt).run();
  await env.db.prepare('INSERT INTO account(account_id,email,name,user_id) VALUES (?,?,?,?)').bind(id, `user${id}@example.com`, `User ${id}`,id).run();
 }
 await env.db.prepare("INSERT INTO role(role_id,name,send_type,avail_domain) VALUES (1,'fixture','count','example.com')").run();
 for (const [id,key] of [[1,'account:query'],[2,'email:send'],[3,'email:delete']]) {
  await env.db.prepare('INSERT INTO perm(perm_id,name,perm_key) VALUES (?,?,?)').bind(id,key,key).run();
  await env.db.prepare('INSERT INTO role_perm(id,role_id,perm_id) VALUES (?,1,?)').bind(id,id).run();
 }
 await env.kv.put('setting:', JSON.stringify({resendTokens:{'example.com':'re_fixture_only'},send:0,bucket:'',endpoint:'',s3AccessKey:'',s3SecretKey:'',domainList:['@example.com']}));
 await insertMail(11,1,'Project sunrise');
 await insertMail(12,1,'Lunch agenda');
 await insertMail(21,2,'Other private subject');
 // Production migration is executed against real isolated D1, including triggers.
 await applyD1Migrations(env.db,env.TEST_MIGRATIONS);
 const tokens = {};
 for (const id of [1,2]) {
  const response = await request('/app/v1/login',null,'POST',{email:`user${id}@example.com`,password:'fixture-password'});
  if (response.code !== 200) throw new Error(`Fixture login failed: ${JSON.stringify(response)}`);
  tokens[id] = response.data.token;
 }
 return tokens;
}

export async function insertMail(id,userId,subject) {
 await env.db.prepare(`INSERT INTO email(email_id,user_id,account_id,send_email,name,subject,text,content,recipient,to_email,message_id,unread) VALUES (?,?,?,'sender@outside.invalid','Sender',?,'Full searchable body beyond list snippets','<p>Hello</p>',?,?,'<fixture@invalid>',0)`).bind(id,userId,userId,subject,JSON.stringify([{address:`user${userId}@example.com`,name:''}]),`user${userId}@example.com`).run();
}

export async function raw(path,token,method='GET',body) {
 return rawUrl(`/api${path}`,token,method,body);
}
export async function rawUrl(path,token,method='GET',body) {
 const ctx = createExecutionContext();
 const headers = new Headers();
 if (token) headers.set('Authorization',token);
 if (body !== undefined) headers.set('Content-Type','application/json');
 const response = await worker.fetch(new Request(`https://fixture.invalid${path}`,{method,headers,body:body === undefined ? undefined : JSON.stringify(body)}),env,ctx);
 await waitOnExecutionContext(ctx);
 return response;
}
export async function request(path,token,method='GET',body) {
 const response = await raw(path,token,method,body);
 const data = await response.json();
 return {...data,httpStatus:response.status};
}
export async function legacyToken(userId=1) {
 const user = await env.db.prepare('SELECT * FROM user WHERE user_id=?').bind(userId).first();
 const payload = {userId,token:`legacy-${userId}`};
 const mapped = {userId,email:user.email,type:1,status:0,isDel:0};
 await env.kv.put(`auth-uid:${userId}`,JSON.stringify({tokens:[payload.token],user:mapped,refreshTime:new Date().toISOString()}));
 return jwtUtils.generateToken({env},payload);
}
