import { env, fetchMock } from 'cloudflare:test';
import { beforeEach, afterEach, describe, expect, it } from 'vitest';
import {setup, request, raw, rawUrl, insertMail, legacyToken} from './fixtures';
import jwtUtils from '../src/utils/jwt-utils';

let tokens;
beforeEach(async () => { tokens=await setup(); fetchMock.activate(); fetchMock.disableNetConnect(); });
afterEach(() => { fetchMock.assertNoPendingInterceptors(); fetchMock.deactivate(); });
const action = (kind,value,id=11,operationId=`fixture-${kind}`) => ({operationId,messageId:id,kind,value});
const send = (operationId='fixture-send') => ({operationId,accountId:1,to:['recipient@outside.invalid'],cc:['cc@outside.invalid'],bcc:['bcc@outside.invalid'],subject:'Synthetic test',text:'hello',html:'<p>hello</p>',attachments:[]});

describe('Native API authentication and resource boundaries', () => {
 it('requires authentication and matches HTTP/envelope error statuses',async () => {
  const result=await request('/app/v1/accounts'); expect(result.httpStatus).toBe(401); expect(result.code).toBe(401);
 });
 it('issues fixed expiry, accepts raw JWT, rejects Bearer prefix and bad password',async () => {
  const payload=await jwtUtils.verifyToken({env},tokens[1]); expect(payload.exp-payload.iat).toBe(30*24*3600);
  expect((await request('/app/v1/accounts',tokens[1])).data.map(x=>x.id)).toEqual([1]);
  expect((await request('/app/v1/accounts',`Bearer ${tokens[1]}`)).code).toBe(401);
  expect((await request('/app/v1/login',null,'POST',{email:'user1@example.com',password:'wrong'})).code).toBe(401);
 });
 it('revokes only current device and rejects cryptographically valid expired tokens',async () => {
  const second=await request('/app/v1/login',null,'POST',{email:'user1@example.com',password:'fixture-password'});
  const payload=await jwtUtils.verifyToken({env},tokens[1]);
  const expired=await jwtUtils.generateToken({env},{...payload,exp:Math.floor(Date.now()/1000)-1});
  expect((await request('/app/v1/accounts',expired)).code).toBe(401);
  for (const exp of [0,null,'not-a-timestamp']) {
   const invalid=await jwtUtils.generateToken({env},{...payload,exp});
   expect((await request('/app/v1/accounts',invalid)).code).toBe(401);
  }
  expect((await request('/app/v1/logout',tokens[1],'DELETE')).code).toBe(200);
  expect((await request('/app/v1/accounts',tokens[1])).code).toBe(401);
  expect((await request('/app/v1/accounts',second.data.token)).code).toBe(200);
 });
 it('revokes native devices atomically on password change or administrative ban',async () => {
  await env.db.prepare("UPDATE user SET password='changed-by-admin' WHERE user_id=1").run();
  expect((await request('/app/v1/accounts',tokens[1])).code).toBe(401);
  expect((await env.db.prepare('SELECT COUNT(*) n FROM app_session WHERE user_id=1').first()).n).toBe(0);
  expect((await request('/app/v1/accounts',tokens[2])).code).toBe(200);
  await env.db.prepare('UPDATE user SET status=1 WHERE user_id=2').run();
  expect((await request('/app/v1/accounts',tokens[2])).code).toBe(401);
 });
 it('keeps existing legacy sessions functional and denies broad auth prefix bypasses',async () => {
  const legacy=await legacyToken();
  expect((await request('/account/list',legacy)).code).toBe(200);
  for (const path of ['/login-evil','/passkey/login-evil','/test','/test/arbitrary']) expect((await request(path)).code).toBe(401);
 });
 it('isolates messages, actions, account selectors, sync cursors and replies by user',async () => {
  expect((await request('/app/v1/messages/21',tokens[1])).code).toBe(404);
  expect((await request('/app/v1/actions',tokens[1],'POST',action('read',true,21))).code).toBe(404);
  const wrongAccount=await request('/app/v1/messages?accountId=2',tokens[1]); expect(wrongAccount.data.messages).toEqual([]);
  const cursor=(await request('/app/v1/sync',tokens[2])).data.cursor;
  expect((await request(`/app/v1/sync?cursor=${encodeURIComponent(cursor)}`,tokens[1])).data.reset).toBe(true);
  expect((await request('/app/v1/send',tokens[1],'POST',{...send(),replyToId:21})).code).toBe(404);
  expect((await request('/app/v1/send',tokens[1],'POST',{...send(),accountId:2})).code).toBe(404);
 });
 it('denies attachment IDOR, deleted parents and public raw object access',async () => {
  await env.db.prepare("INSERT INTO attachments(att_id,user_id,email_id,account_id,key,filename,mime_type,size) VALUES (1,1,11,1,'attachments/private.txt','private.txt','text/html',6)").run();
  await env.r2.put('attachments/private.txt','secret');
  await env.kv.put('attachments/private.txt','secret',{metadata:{contentType:'text/html'}});
  const own=await raw('/app/v1/attachments/1',tokens[1]); expect(own.status).toBe(200); expect(await own.text()).toBe('secret'); expect(own.headers.get('Content-Disposition')).toContain('attachment'); expect(own.headers.get('X-Content-Type-Options')).toBe('nosniff');
  expect((await raw('/app/v1/attachments/1',tokens[2])).status).toBe(404);
  expect((await request('/oss/attachments/private.txt',tokens[2])).code).toBe(404);
  expect((await request('/oss/attachments/private.txt')).code).toBe(401);
  expect((await (await rawUrl('/attachments/private.txt')).json()).code).toBe(401);
  expect((await (await rawUrl('/attachments/private.txt',tokens[2])).json()).code).toBe(404);
  expect(await (await rawUrl('/attachments/private.txt',tokens[1])).text()).toBe('secret');
  await env.db.prepare('UPDATE email SET is_del=1 WHERE email_id=11').run();
  expect((await raw('/app/v1/attachments/1',tokens[1])).status).toBe(404);
 });
 it('denies legacy reply IDOR before sending provider headers or body',async () => {
  const legacy=await legacyToken();
  const result=await request('/email/send',legacy,'POST',{accountId:1,sendType:'reply',emailId:21,receiveEmail:['recipient@outside.invalid'],subject:'fixture',text:'hello',content:'<p>hello</p>',attachments:[]});
  expect(result.code).not.toBe(200);
  expect(await env.db.prepare('SELECT COUNT(*) n FROM email').first()).toEqual({n:3});
 });
 it('authorizes Unicode attachment keys and normalizes inline references without exposing object keys',async () => {
  const key='attachments/測試 圖片.png',encoded=key.split('/').map(encodeURIComponent).join('/');
  await env.db.prepare("INSERT INTO attachments(att_id,user_id,email_id,account_id,key,filename,mime_type,size,type,content_id) VALUES (3,1,11,1,?,'圖片.png','image/png',3,1,'<fixture-image>')").bind(key).run();
  await env.r2.put(key,'png');
  await env.db.prepare('UPDATE email SET content=? WHERE email_id=11').bind(`<p>Inline</p><img src="/api/oss/${encoded}"><img src="cid:fixture-image">`).run();
  const mail=(await request('/app/v1/messages/11',tokens[1])).data;
  expect(mail.attachments).toEqual([]);
  expect(mail.inlineAttachments).toEqual([{id:3,name:'圖片.png',mimeType:'image/png',size:3,reference:'cid:cloud-mail-3'}]);
  expect(mail.html.match(/cid:cloud-mail-3/g)).toHaveLength(2);
  expect(mail.html).not.toContain(encoded);
  expect(await (await raw(`/oss/${encoded}`,tokens[1])).text()).toBe('png');
  expect((await request(`/oss/${encoded}`,tokens[2])).code).toBe(404);
 });
 it('decodes attachment object keys exactly once',async () => {
  const key='attachments/literal%2Fname.txt';
  await env.db.prepare("INSERT INTO attachments(att_id,user_id,email_id,account_id,key,filename,mime_type,size) VALUES (4,1,11,1,?,'literal.txt','text/plain',7)").bind(key).run();
  await env.r2.put(key,'literal');
  const encoded=key.split('/').map(encodeURIComponent).join('/');
  expect(await (await raw(`/oss/${encoded}`,tokens[1])).text()).toBe('literal');
  // A second decode would select another object or bypass association lookup.
  expect((await request('/oss/attachments/literal%2Fname.txt',tokens[1])).code).toBe(404);
 });
 it('bounds actual attachment bytes even when metadata underreports size and strips malicious filename headers',async () => {
  await env.db.prepare('INSERT INTO attachments(att_id,user_id,email_id,account_id,key,filename,mime_type,size) VALUES (2,1,11,1,?,?,?,1)').bind('attachments/large','../../unsafe\r\nInjected: yes.html','text/html').run();
  await env.r2.put('attachments/large',new Uint8Array(25*1024*1024+1));
  expect((await raw('/app/v1/attachments/2',tokens[1])).status).toBe(413);
  await env.r2.put('attachments/large','safe');
  const response=await raw('/app/v1/attachments/2',tokens[1]);expect(response.status).toBe(200);
  expect(response.headers.get('Content-Type')).toBe('application/octet-stream');expect(response.headers.get('Content-Disposition')).not.toMatch(/[\r\n]/);expect(response.headers.get('Injected')).toBeNull();
  expect(response.headers.get('Content-Length')).toBe('4');expect(await response.text()).toBe('safe');
 });
 it('honors role permissions instead of expanding privileges for native clients',async () => {
  await env.db.prepare('DELETE FROM role_perm WHERE perm_id IN (2,3)').run();
  expect((await request('/app/v1/actions',tokens[1],'POST',action('delete',true))).code).toBe(403);
  expect((await request('/app/v1/send',tokens[1],'POST',send())).code).toBe(403);
 });
 it('bounds repeated login attempts and rejects invalid search dates and filters',async () => {
  const responses=[];
  for(let i=0;i<21;i++) responses.push(await request('/app/v1/login',null,'POST',{email:'user1@example.com',password:'incorrect'}));
  expect(responses.at(-1).code).toBe(429);expect(responses.at(-1).httpStatus).toBe(429);
  const limited=await raw('/app/v1/login',null,'POST',{email:'user1@example.com',password:'incorrect'});
  expect(limited.status).toBe(429);expect(limited.headers.get('Retry-After')).toMatch(/^[1-9]\d*$/);
  expect(Number(limited.headers.get('Retry-After'))).toBeLessThanOrEqual(300);
  for(const query of ['after=not-a-date','read=maybe','hasAttachment=maybe','folder=admin']) expect((await request(`/app/v1/messages?${query}`,tokens[1])).code).toBe(400);
  for(const limit of ['1.5','Infinity','NaN']) for(const path of ['messages','sync']) expect((await request(`/app/v1/${path}?limit=${limit}`,tokens[1])).code).toBe(400);
 });
});

describe('D1 transactional sync and offline action reconciliation', () => {
 it('syncs more than 100 messages across bounded pages without dropping or duplicating remote IDs',async () => {
  await env.db.batch(Array.from({length:125},(_,i)=>env.db.prepare("INSERT INTO email(email_id,user_id,account_id,send_email,subject,text) VALUES (?,1,1,'fixture@outside.invalid',?,'fixture body')").bind(100+i,`Paged ${i}`)));
  const ids=[];let cursor='',pages=0;
  do {
   const page=(await request(`/app/v1/sync?limit=20${cursor?`&cursor=${encodeURIComponent(cursor)}`:''}`,tokens[1])).data;
   expect(page.messages.length).toBeLessThanOrEqual(10); ids.push(...page.messages.map(x=>x.id)); cursor=page.cursor; pages++;
   if(!page.hasMore)break;
   expect(pages).toBeLessThan(14);
  }while(true);
  expect(ids).toHaveLength(127); expect(new Set(ids).size).toBe(127); expect(pages).toBe(13);
  expect((await request(`/app/v1/sync?cursor=${encodeURIComponent(cursor)}`,tokens[1])).data.messages).toEqual([]);
 });
 it('keeps a malformed legacy timestamp from preventing whole mailbox synchronization',async () => {
  await env.db.prepare("UPDATE email SET create_time='historically-malformed' WHERE email_id=11").run();
  const page=await request('/app/v1/sync',tokens[1]); expect(page.code).toBe(200);
  expect(page.data.messages.find(x=>x.id===11).receivedAt).toBe('1970-01-01T00:00:00Z');
 });
 it('pages initial state without duplicates and captures newly arriving messages next cycle',async () => {
  const first=await request('/app/v1/sync?limit=1',tokens[1]); expect(first.data.reset).toBe(true); expect(first.data.hasMore).toBe(true);
  await insertMail(13,1,'Arrived while paging');
  const next=await request(`/app/v1/sync?limit=1&cursor=${encodeURIComponent(first.data.cursor)}`,tokens[1]);
  expect(next.data.messages.map(x=>x.id)).not.toContain(13); expect(next.data.hasMore).toBe(false);
  const following=await request(`/app/v1/sync?cursor=${encodeURIComponent(next.data.cursor)}`,tokens[1]); expect(following.data.messages.map(x=>x.id)).toContain(13);
  expect(new Set([...first.data.messages,...next.data.messages].map(x=>x.id)).size).toBe(2);
 });
 it('tracks legacy read, star, deletion, attachment and account transfer writes with tombstones',async () => {
  let cursor=(await request('/app/v1/sync',tokens[1])).data.cursor;
  await env.db.prepare('UPDATE email SET unread=1 WHERE email_id=11').run();
  await env.db.prepare('INSERT INTO star(user_id,email_id) VALUES (1,11)').run();
  let page=(await request(`/app/v1/sync?cursor=${encodeURIComponent(cursor)}`,tokens[1])).data;
  const message=page.messages.find(x=>x.id===11); expect(message.read).toBe(true); expect(message.starred).toBe(true); cursor=page.cursor;
  await env.db.prepare("INSERT INTO attachments(user_id,email_id,account_id,key,filename,size) VALUES (1,11,1,'attachments/a','a.txt',1)").run();
  page=(await request(`/app/v1/sync?cursor=${encodeURIComponent(cursor)}`,tokens[1])).data; expect(page.messages[0].attachments).toHaveLength(1); cursor=page.cursor;
  await env.db.prepare('DELETE FROM email WHERE email_id=12').run();
  page=(await request(`/app/v1/sync?cursor=${encodeURIComponent(cursor)}`,tokens[1])).data; expect(page.messages.find(x=>x.id===12).deleted).toBe(true); cursor=page.cursor;
  await env.db.prepare('UPDATE account SET user_id=2 WHERE account_id=1').run();
  page=(await request(`/app/v1/sync?cursor=${encodeURIComponent(cursor)}`,tokens[1])).data; expect(page.accounts).toEqual([]); expect(page.messages.find(x=>x.id===11).deleted).toBe(true);
 });
 it('safely resets invalid or forged cursors',async () => {
  for (const cursor of ['garbage','eyJ2IjoxLCJ1c2VySWQiOjJ9.fake']) expect((await request(`/app/v1/sync?cursor=${cursor}`,tokens[1])).data.reset).toBe(true);
  const cursor=(await request('/app/v1/sync',tokens[1])).data.cursor;
  await env.db.prepare("UPDATE app_sync_meta SET epoch='new-reviewed-epoch' WHERE id=1").run();
  expect((await request(`/app/v1/sync?cursor=${encodeURIComponent(cursor)}`,tokens[1])).data.reset).toBe(true);
 });
 it('emits both owners after message transfer and tombstones physical account deletion',async () => {
  let oldCursor=(await request('/app/v1/sync',tokens[1])).data.cursor;
  let newCursor=(await request('/app/v1/sync',tokens[2])).data.cursor;
  await env.db.prepare('UPDATE email SET user_id=2,account_id=2 WHERE email_id=11').run();
  let old=(await request(`/app/v1/sync?cursor=${encodeURIComponent(oldCursor)}`,tokens[1])).data;
  let current=(await request(`/app/v1/sync?cursor=${encodeURIComponent(newCursor)}`,tokens[2])).data;
  expect(old.messages.find(x=>x.id===11)).toMatchObject({deleted:true,text:'',html:'',attachments:[]});
  expect(current.messages.find(x=>x.id===11)).toMatchObject({deleted:false,accountId:2}); oldCursor=old.cursor;newCursor=current.cursor;
  await env.db.prepare('DELETE FROM account WHERE account_id=2').run();
  current=(await request(`/app/v1/sync?cursor=${encodeURIComponent(newCursor)}`,tokens[2])).data;
  expect(current.accounts).toEqual([]); expect(current.messages.map(x=>x.deleted)).toEqual([true,true]);
  expect((await request('/app/v1/actions',tokens[2],'POST',action('read',true,11))).code).toBe(404);
 });
 it('replays idempotent action once, rejects changed payload and reconciles multiple devices',async () => {
  const before=await env.db.prepare('SELECT COUNT(*) n FROM app_change_log').first();
  const first=await request('/app/v1/actions',tokens[1],'POST',action('star',true)); expect(first.data.message.starred).toBe(true);
  expect((await request('/app/v1/actions',tokens[1],'POST',action('star',true))).code).toBe(200);
  expect((await request('/app/v1/actions',tokens[1],'POST',action('star',false))).code).toBe(409);
  const after=await env.db.prepare('SELECT COUNT(*) n FROM app_change_log').first(); expect(after.n-before.n).toBe(1);
  await request('/app/v1/actions',tokens[1],'POST',action('delete',true));
  await request('/app/v1/actions',tokens[1],'POST',action('delete',false,11,'fixture-undo'));
  expect((await request('/app/v1/messages/11',tokens[1])).data.deleted).toBe(false);
 });
 it('serializes concurrent identical action replay and rejects a concurrent changed payload',async () => {
  const responses=await Promise.all([request('/app/v1/actions',tokens[1],'POST',action('read',true)),request('/app/v1/actions',tokens[1],'POST',action('read',true)),request('/app/v1/actions',tokens[1],'POST',action('read',false))]);
  expect(responses.filter(x=>x.code===200).length).toBeGreaterThanOrEqual(1); expect(responses.filter(x=>x.code===409).length).toBeGreaterThanOrEqual(1);
  expect((await env.db.prepare("SELECT COUNT(*) n FROM app_action WHERE operation_id='fixture-read'").first()).n).toBe(1);
  const row=await env.db.prepare('SELECT unread FROM email WHERE email_id=11').first();
  const successful=responses.find(x=>x.code===200); expect(successful.data.message.read).toBe(row.unread===1);
 });
 it('searches complete authorized body data and applies folder, unread and attachment filters',async () => {
  const result=await request('/app/v1/messages?q=beyond&read=false',tokens[1]); expect(result.data.messages.map(x=>x.id).sort()).toEqual([11,12]);
  expect((await request('/app/v1/messages?q=Other',tokens[1])).data.messages).toEqual([]);
  expect((await request('/app/v1/messages?folder=sent',tokens[1])).data.messages).toEqual([]);
  expect((await request('/app/v1/messages?hasAttachment=true',tokens[1])).data.messages).toEqual([]);
  const first=await request('/app/v1/messages?limit=1',tokens[1]); expect(first.data.next).toBeTruthy();
  const second=await request(`/app/v1/messages?limit=1&next=${encodeURIComponent(first.data.next)}`,tokens[1]); expect(second.data.messages[0].id).not.toBe(first.data.messages[0].id);
 });
 it('searches long subjects and literal wildcard characters without D1 LIKE pattern limits',async () => {
  const subject='Native HTTP E2E 12345678-1234-1234-1234-123456789abc %_測試';
  await env.db.prepare('UPDATE email SET subject=? WHERE email_id=11').bind(subject).run();
  for(const query of [subject,subject.toLowerCase(),'%_測試']) {
   const result=await request(`/app/v1/messages?q=${encodeURIComponent(query)}&`,tokens[1]);
   expect(result.code).toBe(200);expect(result.data.messages.map(x=>x.id)).toEqual([11]);
  }
  expect((await request(`/app/v1/messages?q=${encodeURIComponent('x'.repeat(200))}`,tokens[1])).data.messages).toEqual([]);
 });
});

describe('Durable send operations with an isolated Resend test double', () => {
 it('sends authorized private inline bytes and uses actual size instead of stale metadata',async () => {
  const key='attachments/inline.png';
  await env.db.prepare("INSERT INTO attachments(att_id,user_id,email_id,account_id,key,filename,mime_type,size,type,content_id) VALUES (3,1,11,1,?,'inline.png','image/png',1,1,'<fixture-image>')").bind(key).run();
  await env.r2.put(key,'png');
  fetchMock.get('https://api.resend.com').intercept({path:'/emails',method:'POST',body:body=>{
   const p=JSON.parse(body);return p.attachments.length===1&&p.attachments[0].content==='cG5n'&&!p.attachments[0].path;
  }}).reply(200,JSON.stringify({id:'fixture-private-inline'}));
  const result=await request('/app/v1/send',tokens[1],'POST',{...send('fixture-inline-send'),html:`<p>Inline</p><img src="${key}">`});
  expect(result.code).toBe(200);expect(result.data.status).toBe('sent');
  const mail=(await request(`/app/v1/messages/${result.data.messageId}`,tokens[1])).data;
  expect(mail.inlineAttachments).toHaveLength(1);expect(mail.inlineAttachments[0].size).toBe(3);
  expect((await request('/app/v1/send',tokens[2],'POST',{...send('fixture-inline-idor'),accountId:2,html:`<img src="${key}">`})).code).toBe(404);
  // The send created a second legitimate owner association to the same object.
  // Remove every owned live parent before asserting the object is inaccessible.
  await env.db.prepare('UPDATE email SET is_del=1 WHERE user_id=1').run();
  expect((await request('/app/v1/send',tokens[1],'POST',{...send('fixture-inline-deleted'),html:`<img src="${key}">`})).code).toBe(404);
 });
 it('rejects CRLF in legacy Message-ID before creating provider threading headers',async () => {
  await env.db.prepare('UPDATE email SET message_id=? WHERE email_id=11').bind('<fixture@invalid>\r\nBcc: injected@outside.invalid').run();
  expect((await request('/app/v1/send',tokens[1],'POST',{...send('fixture-invalid-thread'),replyToId:11})).code).toBe(400);
  expect((await env.db.prepare('SELECT COUNT(*) n FROM email').first()).n).toBe(3);
 });
 it('reserves send quotas atomically across concurrent native requests',async () => {
  await env.db.prepare("UPDATE role SET send_count=1,send_type='count' WHERE role_id=1").run();
  const responses=await Promise.all(['fixture-quota-a','fixture-quota-b'].map(operationId=>request('/app/v1/send',tokens[1],'POST',{...send(operationId),to:['user2@example.com'],cc:[],bcc:[]})));
  expect(responses.filter(x=>x.code===200&&x.data.status==='sent')).toHaveLength(1);
  expect(responses.filter(x=>x.code===403)).toHaveLength(1);
  expect((await env.db.prepare('SELECT send_count FROM user WHERE user_id=1').first()).send_count).toBe(1);
  expect((await env.db.prepare("SELECT COUNT(*) n FROM email WHERE type=1 AND user_id=1").first()).n).toBe(1);
 });
 it('claims concurrent requests once and preserves reply threading headers',async () => {
  let wireMessageId;
  fetchMock.get('https://api.resend.com').intercept({path:'/emails',method:'POST',body:body=>{const p=JSON.parse(body);wireMessageId=p.headers['Message-ID'];return /^<[^<>\s]+@example\.com>$/.test(wireMessageId)&&p.headers['in-reply-to']==='<fixture@invalid>'&&p.headers.references==='<fixture@invalid>'&&p.cc[0]==='cc@outside.invalid'&&p.bcc[0]==='bcc@outside.invalid';}}).reply(200,JSON.stringify({id:'fixture-concurrent-send'}));
  const responses=await Promise.all([request('/app/v1/send',tokens[1],'POST',{...send('fixture-concurrent'),replyToId:11}),request('/app/v1/send',tokens[1],'POST',{...send('fixture-concurrent'),replyToId:11})]);
  expect(responses.map(x=>x.code)).toEqual([200,200]);
  const status=await request('/app/v1/send/fixture-concurrent',tokens[1]); expect(status.data.status).toBe('sent');
  expect((await env.db.prepare("SELECT COUNT(*) n FROM email WHERE resend_email_id='fixture-concurrent-send'").first()).n).toBe(1);
  expect((await env.db.prepare("SELECT message_id FROM email WHERE resend_email_id='fixture-concurrent-send'").first()).message_id).toBe(wireMessageId);
 });
 it('delivers internal mail and attachments without leaking BCC to received copies',async () => {
  const result=await request('/app/v1/send',tokens[1],'POST',{...send('fixture-internal'),to:['user2@example.com'],cc:[],bcc:['user1@example.com'],attachments:[{name:'note.txt',mimeType:'text/plain',contentBase64:'YQ=='}]});
  expect(result.data.status).toBe('sent');
  const sentMail=(await request(`/app/v1/messages/${result.data.messageId}`,tokens[1])).data; expect(sentMail.bcc).toEqual(['user1@example.com']); expect(sentMail.attachments).toHaveLength(1);
  const receiver=(await request('/app/v1/messages?folder=inbox&q=Synthetic',tokens[2])).data.messages; expect(receiver).toHaveLength(1);expect(receiver[0].bcc).toEqual([]);expect(receiver[0].to).toEqual(['user2@example.com']);expect(receiver[0].attachments).toHaveLength(1);
  expect(await (await raw(`/app/v1/attachments/${receiver[0].attachments[0].id}`,tokens[2])).text()).toBe('a');
  expect((await raw(`/app/v1/attachments/${sentMail.attachments[0].id}`,tokens[2])).status).toBe(404);
 });
 it('forwards CC/BCC and provider idempotency, writes one sent copy and never resends on replay',async () => {
  fetchMock.get('https://api.resend.com').intercept({path:'/emails',method:'POST',headers:headers=>Boolean(headers['idempotency-key'])}).reply(200,JSON.stringify({id:'fixture-resend-id'}));
  const first=await request('/app/v1/send',tokens[1],'POST',send()); expect(first.code).toBe(200); expect(first.data.status).toBe('sent');
  const again=await request('/app/v1/send',tokens[1],'POST',send()); expect(again.data.messageId).toBe(first.data.messageId);
  const mail=await request(`/app/v1/messages/${first.data.messageId}`,tokens[1]); expect(mail.data.cc).toEqual(['cc@outside.invalid']); expect(mail.data.bcc).toEqual(['bcc@outside.invalid']);
  expect((await env.db.prepare("SELECT COUNT(*) n FROM email WHERE resend_email_id='fixture-resend-id'").first()).n).toBe(1);
  expect((await request('/app/v1/send',tokens[1],'POST',{...send(),subject:'Changed'})).code).toBe(409);
 });
 it('persists uncertain provider failures and does not blindly resend',async () => {
  fetchMock.get('https://api.resend.com').intercept({path:'/emails',method:'POST'}).replyWithError(new Error('Synthetic interrupted response'));
  const first=await request('/app/v1/send',tokens[1],'POST',send('fixture-uncertain')); expect(first.data.status).toBe('uncertain');
  expect((await request('/app/v1/send',tokens[1],'POST',send('fixture-uncertain'))).data.status).toBe('uncertain');
  expect((await request('/app/v1/send/fixture-uncertain',tokens[2])).code).toBe(404);
 });
 it('rejects malformed addresses, oversized attachment counts and unsafe filenames before provider call',async () => {
  for (const payload of [{...send(),to:['not-email']},{...send(),attachments:[{name:'../secret',mimeType:'text/plain',contentBase64:'YQ=='}]}]) expect((await request('/app/v1/send',tokens[1],'POST',payload)).code).toBe(400);
  expect((await request('/app/v1/send',tokens[1],'POST',{...send(),attachments:Array.from({length:11},()=>({name:'x',mimeType:'text/plain',contentBase64:'YQ=='}))})).code).toBe(413);
 });
});
