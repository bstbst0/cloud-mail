import { defineWorkersConfig, readD1Migrations } from '@cloudflare/vitest-pool-workers/config';

export default defineWorkersConfig(async () => {
 const migrations = await readD1Migrations('./migrations');
 return {
	test: {
		poolOptions: {
			workers: {
				wrangler: { configPath: './wrangler-test.toml' },
				// Isolated local bucket; never bind a remote production R2 bucket.
				miniflare: { r2Buckets: ['r2'], bindings: { TEST_MIGRATIONS: migrations } },
			},
		},
	},
 };
});
