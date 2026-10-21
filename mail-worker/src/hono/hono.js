import { Hono } from 'hono';
const app = new Hono();

import result from '../model/result';
import { cors } from 'hono/cors';

app.use('*', cors());

app.onError((err, c) => {
    if (c.req.path.startsWith('/app/v1/')) {
      const code = err instanceof SyntaxError ? 400 : Number.isInteger(err.code) && err.code>=400 && err.code<=599 ? err.code : 500;
      return c.json(result.fail(code===500?'Internal server error':code===400&&err instanceof SyntaxError?'Invalid JSON':err.message,code),code);
    }
	if (err.name === 'BizError') {
		console.log(err.message);
	} else {
		console.error(err);
	}

	if (err.message === `Cannot read properties of undefined (reading 'get')`) {
		return c.json(result.fail('KV数据库未绑定 KV database not bound',502));
	}

	if (err.message === `Cannot read properties of undefined (reading 'put')`) {
		return c.json(result.fail('KV数据库未绑定 KV database not bound',502));
	}

	if (err.message === `Cannot read properties of undefined (reading 'prepare')`) {
		return c.json(result.fail('D1数据库未绑定 D1 database not bound',502));
	}


	return c.json(result.fail(err.message, err.code));
});

export default app;


