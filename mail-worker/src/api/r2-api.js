import app from '../hono/hono';
import attachmentAccess from '../service/attachment-access-service';
import userContext from '../security/user-context';
import BizError from '../error/biz-error';
app.get('/oss/*', async c => {
 let key;
 try { key=decodeURIComponent(c.req.path.slice('/oss/'.length)); }
 catch { throw new BizError('Attachment not found',404); }
 return attachmentAccess.byKey(c,key,userContext.getUserId(c));
});
