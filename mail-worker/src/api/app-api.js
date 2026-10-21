import app from '../hono/hono';
import result from '../model/result';
import appService from '../service/app-service';
import userContext from '../security/user-context';
import loginService from '../service/login-service';
import { bodyLimit } from 'hono/body-limit';
app.use('/app/v1/*', bodyLimit({maxSize:30*1024*1024,onError:c=>c.json(result.fail('Request exceeds 30 MiB',413),413)}));
const uid=c=>userContext.getUserId(c);
app.post('/app/v1/login',async c=>c.json(result.ok(await appService.login(c,await c.req.json()))));
app.delete('/app/v1/logout',async c=>{
 await loginService.logout(c,uid(c));
 return c.json(result.ok());
});
app.get('/app/v1/accounts',async c=>c.json(result.ok(await appService.accounts(c,uid(c)))));
app.get('/app/v1/sync',async c=>c.json(result.ok(await appService.sync(c,uid(c),c.req.query()))));
app.get('/app/v1/messages',async c=>c.json(result.ok(await appService.messages(c,uid(c),c.req.query()))));
app.get('/app/v1/messages/:id',async c=>c.json(result.ok(await appService.message(c,uid(c),c.req.param('id')))));
app.post('/app/v1/actions',async c=>c.json(result.ok(await appService.action(c,uid(c),await c.req.json()))));
app.post('/app/v1/send',async c=>c.json(result.ok(await appService.send(c,uid(c),await c.req.json()))));
app.get('/app/v1/send/:operationId',async c=>c.json(result.ok(await appService.sendStatus(c,uid(c),c.req.param('operationId')))));
app.get('/app/v1/attachments/:id',async c=>appService.attachment(c,uid(c),c.req.param('id')));
app.get('/app/v1/sessions',async c=>{
 const {results}=await c.env.db.prepare('SELECT token AS id,device,created_at AS createdAt,expires_at AS expiresAt FROM app_session WHERE user_id=? AND expires_at>?').bind(uid(c),Math.floor(Date.now()/1000)).all();
 return c.json(result.ok(results));
});
app.delete('/app/v1/sessions/:id',async c=>{
 await c.env.db.prepare('DELETE FROM app_session WHERE user_id=? AND token=?').bind(uid(c),c.req.param('id')).run();
 return c.json(result.ok());
});
