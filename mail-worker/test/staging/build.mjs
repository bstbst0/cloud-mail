import { createRequire } from 'node:module';
import { resolve, relative, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { mkdir, writeFile } from 'node:fs/promises';
const require = createRequire(import.meta.url);
const wranglerRequire = createRequire(require.resolve('wrangler/package.json'));
const { build } = wranglerRequire('esbuild');
const root = fileURLToPath(new URL('../../', import.meta.url));
const output = process.argv[2];
if (!output) throw new Error('Pass an output directory outside the repository');
const location = relative(resolve(root, '..'), resolve(output));
if (!location.startsWith('..' + (process.platform === 'win32' ? '\\' : '/')) && !isAbsolute(location)) {
  throw new Error('Staging build output must be outside the repository');
}
await mkdir(output, { recursive: true });
process.env.WRANGLER_LOG_PATH ??= resolve(output, 'wrangler-build.log');
const { readD1Migrations } = await import('@cloudflare/vitest-pool-workers/config');
const migrations = await readD1Migrations(resolve(root, 'migrations'));
await build({
  entryPoints: [resolve(root, 'test/staging/entry.mjs')], outfile: resolve(output, 'worker.mjs'),
  bundle: true, minify: true, format: 'esm', platform: 'browser', target: 'es2022',
  banner: { js: "globalThis.fetch=async()=>{throw new Error('Staging outbound network disabled')};" },
  plugins: [{ name: 'reviewed-migrations', setup(build) {
    build.onResolve({ filter: /^staging:migrations$/ }, () => ({ path: 'migrations', namespace: 'staging' }));
    build.onLoad({ filter: /.*/, namespace: 'staging' }, () => ({ contents: `export default ${JSON.stringify(migrations)};`, loader: 'js' }));
  } }]
});
await writeFile(resolve(output, 'wrangler.jsonc'), JSON.stringify({
  name: 'cloud-mail-kmp-stage-build-only', main: './worker.mjs', compatibility_date: '2025-06-04',
  workers_dev: false, routes: [], triggers: { crons: [] }, observability: { enabled: false }
}, null, 2));
console.log('Test-only bundle built; no credentials or production bindings included.');
