import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
let browser;
before(async () => { browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] }); });
after(async () => { await browser?.close(); });
async function pageWithSanitizer() {
  const page = await browser.newPage();
  await page.goto('about:blank');
  const code = await readFile(new URL('../src/utils/mail-html.js', import.meta.url), 'utf8');
  await page.addScriptTag({ content: code.replaceAll('export function ', 'function ') });
  return page;
}
test('allowlist removes active markup, unsafe links, CSS fetches and default remote images', async () => {
  const page = await pageWithSanitizer();
  const result = await page.evaluate(() => safeMailHtml(`<p onclick="evil()" style="color:red;background-image:url(https://tracker.invalid/css)">Hello</p><script>evil()</script><iframe src="https://tracker.invalid"></iframe><svg onload="evil()"></svg><form action="https://evil.invalid"><input></form><img src="https://tracker.invalid/pixel" onerror="evil()"><a href="javascript:evil()">bad</a><a href="cloudmail://delete/all">scheme</a><a href="https://example.com/read">good</a>`));
  assert.match(result, /Hello/); assert.match(result, /color: red/);
  assert.doesNotMatch(result, /onclick|onerror|script|iframe|svg|form|input|tracker|javascript|cloudmail:|background-image/i);
  assert.match(result, /https:\/\/example.com\/read/);
  await page.close();
});
test('preprocessing makes no network requests and isolated mail cannot run script or tracking', async () => {
  const page = await pageWithSanitizer();
  const requests = [];
  await page.route('https://tracker.invalid/**', route => { requests.push(route.request().url()); return route.abort(); });
  await page.evaluate(() => {
    window.mailExecuted = false;
    const iframe = document.createElement('iframe');
    iframe.setAttribute('sandbox', 'allow-same-origin');
    iframe.srcdoc = mailDocument(`<script>parent.mailExecuted=true</script><img src="https://tracker.invalid/pixel" onerror="parent.mailExecuted=true"><p style="background-image:url(https://tracker.invalid/css)">Safe content</p>`);
    document.body.appendChild(iframe);
  });
  await page.waitForFunction(() => document.querySelector('iframe')?.contentDocument?.body?.textContent.includes('Safe content'));
  assert.equal(await page.evaluate(() => window.mailExecuted), false);
  assert.deepEqual(requests, []);
  await page.close();
});
test('remote image consent only allows HTTPS and never enables scripts, frames or data SVG', async () => {
  const page = await pageWithSanitizer();
  const result = await page.evaluate(() => safeMailHtml('<img src="https://images.example.com/picture"><img src="http://images.example.com/pixel"><img src="data:image/svg+xml,test"><img src="https://user:secret@images.example.com/pixel"><img src="file:///etc/passwd"><script>bad()</script>', { remoteImages: true }));
  assert.match(result, /src="https:\/\/images.example.com\/picture"/);
  assert.doesNotMatch(result, /http:|data:|secret|file:|script/);
  await page.close();
});
test('mail text quoting escapes hostile sender names and plain text', async () => {
  const page = await pageWithSanitizer();
  assert.equal(await page.evaluate(() => escapeMailText('<img src=x onerror=evil()>&')), '&lt;img src=x onerror=evil()&gt;&amp;');
  await page.close();
});

test('attachment credentials stay on configured API and Unicode filenames remain compatible', async () => {
  const page = await browser.newPage();
  await page.route('https://mail.example/**', route => route.fulfill({ body: '<html><body>Fixture</body></html>', contentType: 'text/html' }));
  await page.goto('https://mail.example');
  const code = await readFile(new URL('../src/utils/private-attachments.js', import.meta.url), 'utf8');
  await page.addScriptTag({ content: code.replaceAll('export async function ', 'async function ').replaceAll('export function ', 'function ').replace('import.meta.env.VITE_BASE_URL', "'/api'") });
  const result = await page.evaluate(async () => {
    localStorage.setItem('token', 'fixture-jwt');
    let target, authorization;
    window.fetch = async (url, options) => { target = url; authorization = options.headers.Authorization; return new Response('fixture', { headers: { 'Content-Type': 'application/octet-stream' } }); };
    const blob = await privateAttachmentBlob('attachments/hash.郵件');
    const invalid = [];
    for (const key of ['https://evil.example/key', 'attachments/../secret', 'attachments/a/b', 'attachments/..', 'attachments/']) {
      try { await privateAttachmentBlob(key); invalid.push(key); } catch { /* Must reject. */ }
    }
    return { target, authorization, content: await blob.text(), invalid, name: safeFilename('../a\u0000.txt') };
  });
  assert.equal(result.target, '/api/oss/attachments/hash.%E9%83%B5%E4%BB%B6');
  assert.equal(result.authorization, 'fixture-jwt'); assert.equal(result.content, 'fixture'); assert.deepEqual(result.invalid, []);
  assert.doesNotMatch(result.name, /[\/\u0000]/);
  await page.close();
});

test('attachment stream stops and cancels after size cap even without Content-Length', async () => {
  const page = await browser.newPage();
  const code = await readFile(new URL('../src/utils/private-attachments.js', import.meta.url), 'utf8');
  await page.addScriptTag({ content: code.replaceAll('export async function ', 'async function ').replaceAll('export function ', 'function ').replace('import.meta.env.VITE_BASE_URL', "'/api'").replace("localStorage.getItem('token')", "'fixture-jwt'") });
  const result = await page.evaluate(async () => {
    let cancelled = false;
    window.fetch = async () => new Response(new ReadableStream({ pull(controller) { controller.enqueue(new Uint8Array(13 * 1024 * 1024)); }, cancel() { cancelled = true; } }));
    let message;
    try { await privateAttachmentBlob('attachments/hash.bin'); } catch (error) { message = error.message; }
    return { cancelled, message };
  });
  assert.equal(result.cancelled, true); assert.match(result.message, /exceeds 25 MiB/);
  await page.close();
});
