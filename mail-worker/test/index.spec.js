import { createExecutionContext, waitOnExecutionContext } from 'cloudflare:test';
import { describe, it, expect } from 'vitest';
import worker from '../src';
import saltHashUtils from '../src/utils/crypto-utils';
import kvObjService from '../src/service/kv-obj-service';

describe('Cloud Mail worker', () => {
	it('serves the SPA asset handler for non-API requests', async () => {
		const request = new Request('http://example.com');
		const ctx = createExecutionContext();
		const response = await worker.fetch(request, {
			assets: {
				fetch: async () => new Response('asset-ok', { status: 200 })
			}
		}, ctx);
		await waitOnExecutionContext(ctx);

		expect(response.status).toBe(200);
		expect(await response.text()).toBe('asset-ok');
	});

	it('generates cryptographically random passwords with the requested length', () => {
		const password = saltHashUtils.genRandomPwd(32);
		expect(password).toHaveLength(32);
		expect(password).toMatch(/^[A-Za-z0-9]+$/);
	});

	it('returns a 404 response for missing KV-backed objects', async () => {
		const response = await kvObjService.toObjResp({
			env: {
				kv: {
					getWithMetadata: async () => ({ value: null, metadata: null })
				}
			}
		}, 'attachments/missing.txt');

		expect(response.status).toBe(404);
	});
});
