# Disposable Cloudflare staging harness

Test-only code. Never deploy `entry.mjs` as the production Worker. No production config or source is modified. Use only freshly created resources; never production bindings, exported production mail, routing, credentials or providers.

1. Install the pinned Worker dependencies with `npm ci`. Build outside the repository:

   ```sh
   node test/staging/build.mjs /absolute/private/staging-build
   node test/staging/validate-local.mjs /absolute/private/staging-build/worker.mjs
   ```

2. Create a uniquely named Worker, empty D1, KV namespace and private R2 bucket, all with a `cloud-mail-kmp-stage-` prefix. Use Cloudflare API/Wrangler with a dedicated reviewed configuration outside the repository. Do not use the repository's production deploy workflow/config. Keep a resource creation ledger for exact cleanup.
3. Create a private JSON file (directory mode 0700, file 0600) outside the repository containing `name`, `subdomain`, `url` (exact HTTPS origin without trailing slash), `account`, `d1`, `kv`, `r2`, random 64-hex `gate`, independently random `jwt`, synthetic random `password` (at least 32 characters), and `expiresAt` (Unix milliseconds, e.g. four hours ahead). Never commit or print it.
4. Upload the generated ESM bundle with `main_module=worker.mjs`, compatibility date `2025-06-04`, observability disabled, and **only** these bindings:

   - `db`: newly created D1; `kv`: newly created namespace; `r2`: newly created private bucket.
   - Secret text `jwt_secret=jwt`, `STAGING_GATE=gate`.
   - Plain text `STAGING_RUN_ID=name`, `STAGING_EXPIRES_AT=expiresAt`, `admin=admin@staging.invalid`.
   - JSON `domain=["example.com"]`, `orm_log=false`.

   Enable only that new Worker's workers.dev endpoint. No custom routes, assets, cron, service/provider bindings or email handlers. Verify actual bindings and disabled public R2 access before testing. The generated build-only Wrangler configuration intentionally has no remote bindings and workers.dev disabled; it is suitable for dry-run, not a ready deployment manifest.
5. Run the initialization verification **once** on the empty database (bootstrap refuses an initialized database):

   ```sh
   node --use-env-proxy test/staging/verify-remote.mjs /absolute/private/run.json
   ```

6. From `mail-kmp`, run the actual native integration test with JDK 21 and the configured Android SDK/Gradle environment:

   ```sh
   CLOUD_MAIL_STAGING_CONFIG=/absolute/private/run.json ./gradlew --dependency-verification=strict :composeApp:desktopTest --tests '*WorkerHttpIntegrationTest'
   ```

   This accepts only the explicitly named staging workers.dev origin, checks its secret-gated identity before login/writes, disables redirects and uses `HTTPS_PROXY` only in this staging test when set. Credentials are not logged. HTTP integration tasks always execute instead of reusing Gradle results.
7. Run the additional checks from `mail-worker`:

   ```sh
   node --use-env-proxy test/staging/verify-compatibility.mjs /absolute/private/run.json
   node --use-env-proxy test/staging/verify-isolation.mjs /absolute/private/run.json
   npm test
   ```

   Compatibility checks mutate synthetic message 12; run them once per fixture. The HTTP test uses UUIDs for send operations. Never blindly retry external sends; this harness has no provider keys and blocks all outbound fetches.
8. Record actual results without secrets. Delete only the exact Worker/resources created in step 2: Worker first, then objects in its new bucket and the bucket, KV namespace and D1. Verify API cleanup responses; delete the private credential file when no longer needed. Expiry blocks HTTP access but does not delete resources or stop storage charges.

The local `test/http-fixture.mjs` remains the default CI fixture. Set only `CLOUD_MAIL_E2E_URL=http://127.0.0.1:8789` for that fixture, with staging config unset. It requires no Cloudflare account.
