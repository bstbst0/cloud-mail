import BizError from '../error/biz-error';
import jwtUtils from '../utils/jwt-utils';
import cryptoUtils from '../utils/crypto-utils';
import userService from './user-service';
import emailService from './email-service';
import permService from './perm-service';
import attachmentAccess from './attachment-access-service';
import {parseHTML} from 'linkedom';

const TTL = 30 * 24 * 60 * 60;
const now = () => Math.floor(Date.now() / 1000);
const numberId = v => { const n = Number(v); if (!Number.isSafeInteger(n) || n <= 0) throw new BizError('Invalid resource ID', 400); return n; };
const limitOf = v => { if(v===undefined||v==='')return 10;const n=Number(v);if(!Number.isSafeInteger(n)||n<1)throw new BizError('Invalid page limit',400);return Math.min(10,n); };
const operation = v => { if (typeof v !== 'string' || !/^[A-Za-z0-9_-]{8,128}$/.test(v)) throw new BizError('Invalid operation ID', 400); return v; };
const listJson = value => { try { return JSON.parse(value || '[]').map(v => typeof v === 'string' ? v : v.address).filter(Boolean); } catch { return []; } };
const select = `SELECT e.*, EXISTS(SELECT 1 FROM star s WHERE s.email_id=e.email_id AND s.user_id=e.user_id) AS starred,
 CASE WHEN a.account_id IS NULL OR a.is_del!=0 OR a.user_id!=e.user_id THEN 1 ELSE 0 END AS account_deleted
 FROM email e LEFT JOIN account a ON a.account_id=e.account_id`;
export const tombstone = id => ({ id, accountId:0, fromAddress:'', fromName:'', subject:'', text:'', html:'', to:[], cc:[], bcc:[], receivedAt:'1970-01-01T00:00:00Z', folder:'inbox', read:false, starred:false, deleted:true, status:0, attachments:[], inlineAttachments:[] });
export async function fingerprint(value) {
 const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify(value)));
 return Array.from(new Uint8Array(hash), b => b.toString(16).padStart(2,'0')).join('');
}
async function permission(c, userId, key) {
 if (c.get('user')?.email === c.env.admin) return;
 if (!(await permService.userPermKeys(c,userId)).includes(key)) throw new BizError('Forbidden',403);
}
async function rate(c, bucket, max, seconds) {
 const window = Math.floor(now()/seconds);
 const row = await c.env.db.prepare(`INSERT INTO app_rate_limit(bucket,attempts,expires_at) VALUES(?,1,?) ON CONFLICT(bucket) DO UPDATE SET attempts=attempts+1 RETURNING attempts`).bind(`${bucket}:${window}`,now()+seconds).first();
 if (row.attempts > max) {
  c.header('Retry-After',String(Math.max(1,(window+1)*seconds-now())));
  throw new BizError('Too many requests',429);
 }
 // Opportunistic bounded cleanup. Contains no credentials or message content.
 await c.env.db.prepare('DELETE FROM app_rate_limit WHERE bucket IN (SELECT bucket FROM app_rate_limit WHERE expires_at<? LIMIT 100)').bind(now()).run();
}
const attachmentDto=a=>({id:a.att_id,name:a.filename||'attachment',mimeType:a.mime_type||'application/octet-stream',size:a.size||0});
function normalizeInlineHtml(c,html,attachments) {
 const inline=attachments.filter(a=>a.type===1||a.content_id);
 if(!html||!inline.length)return html;
 const {document}=parseHTML(html);
 const origin=new URL(c.req.url).origin;
 for(const image of document.querySelectorAll('img')) {
  const src=image.getAttribute('src');
  const match=inline.find(a=>[a.key,a.key.split('/').map(encodeURIComponent).join('/')].some(key=>[key,`/${key}`,`{{domain}}${key}`,`${origin}/${key}`,`${origin}/api/oss/${key}`,`/api/oss/${key}`].includes(src))||(a.content_id&&src===`cid:${a.content_id.replace(/^<|>$/g,'')}`));
  if(match)image.setAttribute('src',`cid:cloud-mail-${match.att_id}`);
 }
 return document.toString();
}
async function dto(c,row,userId,attachments) {
 if (!row || row.is_del || row.account_deleted || row.user_id !== userId || row.status===6) return tombstone(row?.email_id || 0);
 const results = attachments || (await c.env.db.prepare('SELECT att_id,filename,mime_type,size,key,type,content_id FROM attachments WHERE email_id=? AND user_id=? ORDER BY att_id').bind(row.email_id,userId).all()).results;
 const rawDate=typeof row.create_time==='string' ? row.create_time : '';
 const date=new Date(/[zZ]|[+-]\d\d:\d\d$/.test(rawDate)?rawDate:rawDate.replace(' ','T')+'Z');
 const receivedAt=Number.isFinite(date.getTime())?date.toISOString():'1970-01-01T00:00:00Z';
 return {id:row.email_id,accountId:row.account_id,fromAddress:row.send_email||'',fromName:row.name||'',subject:row.subject||'',text:row.text||'',html:normalizeInlineHtml(c,row.content||'',results),to:listJson(row.recipient),cc:listJson(row.cc),bcc:row.type===1?listJson(row.bcc):[],receivedAt,folder:row.type===1?'sent':'inbox',read:row.unread===1,starred:!!row.starred,deleted:false,status:row.status,attachments:results.filter(a=>a.type===0&&!a.content_id).map(attachmentDto),inlineAttachments:results.filter(a=>a.type===1||a.content_id).map(a=>({...attachmentDto(a),reference:`cid:cloud-mail-${a.att_id}`}))};
}
async function messageDtos(c,rows,userId) {
 const ids=rows.filter(r=>!r.is_del&&!r.account_deleted&&r.status!==6).map(r=>r.email_id);
 const attachments=ids.length ? (await c.env.db.prepare(`SELECT email_id,att_id,filename,mime_type,size,key,type,content_id FROM attachments WHERE user_id=? AND email_id IN (${ids.map(()=>'?').join(',')}) ORDER BY att_id`).bind(userId,...ids).all()).results : [];
 return Promise.all(rows.map(row=>dto(c,row,userId,attachments.filter(a=>a.email_id===row.email_id))));
}
async function cursor(c,payload) { return jwtUtils.generateToken(c,{scope:'sync-v1',...payload}); }
const appService = {
 async login(c,params) {
  await rate(c,`login:${c.req.header('cf-connecting-ip')||'local'}`,20,300);
  const {email,password} = params;
  if(typeof email!=='string'||typeof password!=='string'||email.length>320||password.length>256) throw new BizError('Invalid credentials',400);
  const user = await userService.selectByEmailIncludeDel(c,email);
  if(!user||user.isDel||user.status||!await cryptoUtils.verifyPassword(password,user.salt,user.password)) throw new BizError('Invalid credentials',401);
  const tokenId = crypto.randomUUID(), expiresAt = now()+TTL;
  await c.env.db.batch([
   c.env.db.prepare('DELETE FROM app_session WHERE user_id=? AND expires_at<=?').bind(user.userId,now()),
   c.env.db.prepare('DELETE FROM app_session WHERE token IN (SELECT token FROM app_session WHERE user_id=? ORDER BY created_at DESC,token DESC LIMIT -1 OFFSET 19)').bind(user.userId),
   c.env.db.prepare('INSERT INTO app_session(token,user_id,expires_at,created_at) VALUES(?,?,?,?)').bind(tokenId,user.userId,expiresAt,now())
  ]);
  const token = await jwtUtils.generateToken(c,{scope:'app-v1',userId:user.userId,token:tokenId},TTL);
  return {token,userId:user.userId,email:user.email,expiresAt};
 },
 async accounts(c,userId) {
  await permission(c,userId,'account:query');
  const {results} = await c.env.db.prepare('SELECT account_id AS id,email,name FROM account WHERE user_id=? AND is_del=0 ORDER BY sort DESC,account_id').bind(userId).all();
  return results;
 },
 async sync(c,userId,query) {
  await rate(c,`sync:${userId}`,120,60);
  const meta = await c.env.db.prepare('SELECT epoch FROM app_sync_meta WHERE id=1').first();
  const top = await c.env.db.prepare('SELECT COALESCE(MAX(seq),0) AS seq FROM app_change_log').first();
  const incoming = query.cursor ? await jwtUtils.verifyToken(c,query.cursor) : null;
  const valid = incoming?.scope==='sync-v1'&&incoming.v===1&&incoming.userId===userId&&incoming.epoch===meta.epoch&&Number.isSafeInteger(incoming.after)&&incoming.after>=0&&incoming.after<=top.seq&&(incoming.until===null||(Number.isSafeInteger(incoming.until)&&incoming.until>=incoming.after&&incoming.until<=top.seq));
  const after = valid ? incoming.after : 0;
  const until = valid && incoming.until!==null ? incoming.until : top.seq;
  const {results} = await c.env.db.prepare('SELECT seq,message_id FROM app_change_log WHERE user_id=? AND seq>? AND seq<=? ORDER BY seq LIMIT ?').bind(userId,after,until,limitOf(query.limit)+1).all();
  const hasMore = results.length>limitOf(query.limit), events=results.slice(0,limitOf(query.limit));
  const ids=[...new Set(events.map(e=>e.message_id).filter(Boolean))];
  const rows=ids.length ? (await c.env.db.prepare(`${select} WHERE e.user_id=? AND e.email_id IN (${ids.map(()=>'?').join(',')})`).bind(userId,...ids).all()).results : [];
  const mapped=await messageDtos(c,rows,userId);
  const messages=ids.map(id=>mapped.find(m=>m.id===id)||tombstone(id));
  const nextAfter = hasMore ? events.at(-1).seq : until;
  return {messages,accounts:await this.accounts(c,userId),cursor:await cursor(c,{v:1,epoch:meta.epoch,userId,after:nextAfter,until:hasMore?until:null}),hasMore,reset:!valid};
 },
 async message(c,userId,id,includeDeleted=false) {
  const row = await c.env.db.prepare(`${select} WHERE e.email_id=? AND e.user_id=?`).bind(numberId(id),userId).first();
  if(!row||row.account_deleted||(!includeDeleted&&row.is_del)) throw new BizError('Message not found',404);
  return dto(c,row,userId);
 },
 async messages(c,userId,q) {
  await rate(c,`search:${userId}`,120,60);
  const conditions=['e.user_id=?','e.is_del=0','a.is_del=0','a.user_id=e.user_id','e.status!=6'],args=[userId];
  if(q.accountId) {conditions.push('e.account_id=?');args.push(numberId(q.accountId));}
  if(q.folder==='inbox'||q.folder==='sent') {conditions.push('e.type=?');args.push(q.folder==='sent'?1:0);}
  else if(q.folder==='starred') conditions.push('EXISTS(SELECT 1 FROM star s WHERE s.user_id=e.user_id AND s.email_id=e.email_id)');
  else if(q.folder) throw new BizError('Invalid folder',400);
  if(q.q) {
   if(q.q.length>200) throw new BizError('Query too long',400);
   // D1 limits LIKE/GLOB pattern bytes. A complete subject plus UUID can exceed
   // that budget; instr preserves literal substring semantics without patterns.
   const contains=column=>`instr(lower(COALESCE(${column},'')),lower(?))>0`;
   conditions.push(`(${['e.subject','e.send_email','e.recipient','e.text','e.name','e.content','e.cc'].map(contains).join(' OR ')} OR (e.type=1 AND ${contains('e.bcc')}))`);
   args.push(...Array(8).fill(q.q));
  }
  for(const [key,column] of [['after','>='],['before','<=']]) if(q[key]) {const date=new Date(q[key]);if(!Number.isFinite(date.getTime()))throw new BizError('Invalid date',400);const inclusiveDayEnd=key==='before'&&/^\d{4}-\d{2}-\d{2}$/.test(q[key]);if(inclusiveDayEnd)date.setUTCDate(date.getUTCDate()+1);conditions.push(`e.create_time ${inclusiveDayEnd?'<':column} ?`);args.push(date.toISOString().slice(0,19).replace('T',' '));}
  if(q.read!==undefined) {if(!['true','false'].includes(q.read)) throw new BizError('Invalid read filter',400);conditions.push('e.unread=?');args.push(q.read==='true'?1:0);}
  if(q.hasAttachment!==undefined) {if(!['true','false'].includes(q.hasAttachment)) throw new BizError('Invalid attachment filter',400);conditions.push(`${q.hasAttachment==='false'?'NOT ':''}EXISTS(SELECT 1 FROM attachments x WHERE x.email_id=e.email_id AND x.user_id=e.user_id AND x.type=0)`);}
  if(q.next) {conditions.push('e.email_id<?');args.push(numberId(q.next));}
  const limit=limitOf(q.limit);
  const {results}=await c.env.db.prepare(`${select} WHERE ${conditions.join(' AND ')} ORDER BY e.email_id DESC LIMIT ?`).bind(...args,limit+1).all();
  const rows=results.slice(0,limit);
  return {messages:await messageDtos(c,rows,userId),next:results.length>limit?String(rows.at(-1).email_id):null};
 },
 async action(c,userId,p) {
  const operationId=operation(p.operationId),messageId=numberId(p.messageId);
  if(!['read','star','delete'].includes(p.kind)||typeof p.value!=='boolean') throw new BizError('Invalid action',400);
  if(p.kind==='delete') await permission(c,userId,'email:delete');
  await this.message(c,userId,messageId,true);
  const hash=await fingerprint({messageId,kind:p.kind,value:p.value});
  const old=await c.env.db.prepare('SELECT fingerprint FROM app_action WHERE user_id=? AND operation_id=?').bind(userId,operationId).first();
  if(old&&old.fingerprint!==hash) throw new BizError('Operation ID reused with different payload',409);
  if(!old) {
   const gate='NOT EXISTS(SELECT 1 FROM app_action WHERE user_id=? AND operation_id=?)';
   const owns='EXISTS(SELECT 1 FROM account a WHERE a.account_id=email.account_id AND a.user_id=email.user_id AND a.is_del=0)';
   const statements=[];
   if(p.kind==='read'||p.kind==='delete') statements.push(c.env.db.prepare(`UPDATE email SET ${p.kind==='read'?'unread':'is_del'}=? WHERE email_id=? AND user_id=? AND ${owns} AND ${gate}`).bind(p.value?1:0,messageId,userId,userId,operationId));
   else if(p.value) statements.push(c.env.db.prepare(`INSERT INTO star(user_id,email_id) SELECT ?,? WHERE EXISTS(SELECT 1 FROM email WHERE user_id=? AND email_id=? AND is_del=0 AND ${owns}) AND NOT EXISTS(SELECT 1 FROM star WHERE user_id=? AND email_id=?) AND ${gate}`).bind(userId,messageId,userId,messageId,userId,messageId,userId,operationId));
   else statements.push(c.env.db.prepare(`DELETE FROM star WHERE email_id=? AND user_id=? AND EXISTS(SELECT 1 FROM email WHERE email.email_id=star.email_id AND email.user_id=star.user_id AND ${owns}) AND ${gate}`).bind(messageId,userId,userId,operationId));
   statements.push(c.env.db.prepare(`INSERT OR IGNORE INTO app_action(user_id,operation_id,fingerprint,created_at) SELECT ?,?,?,? WHERE EXISTS(SELECT 1 FROM email WHERE email_id=? AND user_id=? AND ${owns})`).bind(userId,operationId,hash,now(),messageId,userId));
   await c.env.db.batch(statements);
   const saved=await c.env.db.prepare('SELECT fingerprint FROM app_action WHERE user_id=? AND operation_id=?').bind(userId,operationId).first();
   if(!saved)throw new BizError('Message not found',404);
   if(saved.fingerprint!==hash) throw new BizError('Operation ID reused with different payload',409);
  }
  return {operationId,message:await this.message(c,userId,messageId,true)};
 },
 async sendStatus(c,userId,id) {
  const row=await c.env.db.prepare('SELECT status,message_id,created_at FROM app_send_operation WHERE user_id=? AND operation_id=?').bind(userId,operation(id)).first();
  if(!row) throw new BizError('Send operation not found',404);
  return {operationId:id,status:row.status==='pending'&&row.created_at<now()-300?'uncertain':row.status,messageId:row.message_id||null};
 },
 async send(c,userId,p) {
  await rate(c,`send:${userId}`,30,3600);
  await permission(c,userId,'email:send');
  const operationId=operation(p.operationId),accountId=numberId(p.accountId);
  const account=await c.env.db.prepare('SELECT account_id FROM account WHERE account_id=? AND user_id=? AND is_del=0').bind(accountId,userId).first();
  if(!account) throw new BizError('Sender account not found',404);
  const recipients=[p.to,p.cc||[],p.bcc||[]];
  if(!Array.isArray(p.to)||!p.to.length||recipients.some(list=>!Array.isArray(list))||recipients.flat().length>50||recipients.flat().some(a=>typeof a!=='string'||a.length>320||!/^([^\s<>@]+)@([^\s<>@]+\.[^\s<>@]+)$/.test(a))) throw new BizError('Invalid recipients',400);
  if(typeof p.subject!=='string'||p.subject.length>998||/[\r\n]/.test(p.subject)||typeof p.text!=='string'||typeof p.html!=='string'||p.text.length+p.html.length>1024*1024) throw new BizError('Invalid message body',400);
  if(p.replyToId) await this.message(c,userId,p.replyToId);
  const attachments=p.attachments||[];
  if(!Array.isArray(attachments)||attachments.length>10)throw new BizError('Too many attachments',413);
  let size=0;
  for(const a of attachments) {if(typeof a.name!=='string'||!a.name||a.name.length>255||/[\\/\x00-\x1f\x7f]/.test(a.name)||typeof a.mimeType!=='string'||!/^[-\w.+]+\/[-\w.+]+$/.test(a.mimeType)||typeof a.contentBase64!=='string'||! /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(a.contentBase64))throw new BizError('Invalid attachment',400);size+=a.contentBase64.length*3/4;}
  if(size>20*1024*1024)throw new BizError('Attachments exceed 20 MiB',413);
  const hash=await fingerprint({accountId,to:p.to,cc:p.cc||[],bcc:p.bcc||[],subject:p.subject,text:p.text,html:p.html,replyToId:p.replyToId||null,attachments});
  const claim=await c.env.db.prepare("INSERT OR IGNORE INTO app_send_operation(user_id,operation_id,fingerprint,status,created_at) VALUES(?,?,?,'pending',?)").bind(userId,operationId,hash,now()).run();
  const saved=await c.env.db.prepare('SELECT fingerprint FROM app_send_operation WHERE user_id=? AND operation_id=?').bind(userId,operationId).first();
  if(saved.fingerprint!==hash)throw new BizError('Operation ID reused with different payload',409);
  if(!claim.meta.changes) return this.sendStatus(c,userId,operationId);
  try {
   const sent=await emailService.send(c,{accountId,receiveEmail:p.to,cc:p.cc||[],bcc:p.bcc||[],subject:p.subject,text:p.text,content:p.html,sendType:p.replyToId?'reply':'send',emailId:p.replyToId,attachments:attachments.map(a=>({filename:a.name,type:a.mimeType,content:a.contentBase64})),idempotencyKey:`cloud-mail/${userId}/${operationId}`,appOperationId:operationId},userId);
   await c.env.db.prepare("UPDATE app_send_operation SET status='sent',message_id=? WHERE user_id=? AND operation_id=?").bind(sent[0].emailId,userId,operationId).run();
  } catch(e) {
   // Conservative: provider may have accepted before network/database/storage failed.
   await c.env.db.prepare("UPDATE app_send_operation SET status=? WHERE user_id=? AND operation_id=?").bind(e.name==='BizError'&&!c.get('providerAttempted')?'failed':'uncertain',userId,operationId).run();
   if(e.name==='BizError'&&!c.get('providerAttempted')) throw e;
  }
  return this.sendStatus(c,userId,operationId);
 },
 async attachment(c,userId,id) { return attachmentAccess.byId(c,numberId(id),userId); }
};
export default appService;
