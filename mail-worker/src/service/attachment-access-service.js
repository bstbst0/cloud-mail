import BizError from '../error/biz-error';
import r2Service from './r2-service';
import permService from './perm-service';

const attachmentAccess = {
 async authorized(c, field, value, userId) {
  if(field==='key'&&(typeof value!=='string'||!value.startsWith('attachments/')||/[\x00-\x1f\\]/.test(value)||value.split('/').some(p=>p==='.'||p==='..')))throw new BizError('Attachment not found',404);
  const query=`SELECT a.* FROM attachments a JOIN email e ON e.email_id=a.email_id JOIN account m ON m.account_id=e.account_id WHERE a.${field}=? AND e.is_del=0 AND m.is_del=0 AND e.user_id=a.user_id AND m.user_id=e.user_id`;
  let row=await c.env.db.prepare(`${query} AND a.user_id=? LIMIT 1`).bind(value,userId).first();
  if(!row&&(c.get('user')?.email===c.env.admin||(await permService.userPermKeys(c,userId)).includes('all-email:query')))row=await c.env.db.prepare(`${query} LIMIT 1`).bind(value).first();
  if(!row)throw new BizError('Attachment not found',404);
  return row;
 },
 async response(c,row) {
  if(row.size>25*1024*1024)throw new BizError('Attachment exceeds download limit',413);
  const obj=await r2Service.getObj(c,row.key);
  if(!obj)throw new BizError('Attachment not found',404);
  if(obj.size>25*1024*1024) {
   await obj.body?.cancel?.();
   throw new BizError('Attachment exceeds download limit',413);
  }
  const filename=(row.filename||'attachment').replace(/[\x00-\x1f\x7f\\/]/g,'_').slice(0,255);
  // Always download: HTML/SVG and misleading server metadata cannot execute in origin.
  return new Response(obj.body,{headers:{'Content-Type':'application/octet-stream','Content-Disposition':`attachment; filename="attachment"; filename*=UTF-8''${encodeURIComponent(filename).replace(/'/g,'%27')}`,'X-Content-Type-Options':'nosniff','Cache-Control':'private, no-store','Content-Security-Policy':"default-src 'none'; sandbox",...(Number.isSafeInteger(obj.size)&&obj.size>=0?{'Content-Length':String(obj.size)}:{})}});
 },
 async byId(c,id,userId) {return this.response(c,await this.authorized(c,'att_id',id,userId));},
 async byKey(c,key,userId) {return this.response(c,await this.authorized(c,'key',key,userId));}
};
export default attachmentAccess;
