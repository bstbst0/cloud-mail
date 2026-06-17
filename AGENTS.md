# AGENTS.md

## 專案結構

- `mail-worker/`: Cloudflare Worker 後端，入口為 `src/index.js`，Hono 路由集中由 `src/hono/webs.js` 匯入。
- `mail-vue/`: Vue 3 + Element Plus 前端，release build 會輸出到 `mail-worker/dist`。

## 常用驗證命令

- 後端測試：在 `mail-worker/` 執行 `npm test`。
- 前端建置：在 `mail-vue/` 執行 `.\node_modules\.bin\vite.cmd build --mode release`。
- diff 檢查：在 repo 根目錄執行 `git diff --check`。

## 維護注意事項

- 修改 Worker 測試時，`vitest.config.js` 使用 `wrangler-test.toml`，不要改回不存在的 `wrangler.jsonc`。
- `mail-worker/package.json` 的 `test` 是本地 Vitest；測試部署請使用 `deploy:test`。
- 前端 release 環境不要在 `.env.release` 設定 `NODE_ENV=release`，Vite 只支援用 `mode` 區分 release。
- 權限新增 API 時，需要同步檢查 `mail-worker/src/security/security.js` 的 `requirePerms` 和 `premKey`。
- 背景圖若走 KV 儲存可使用相對路徑；R2/S3 儲存需要設定 `r2Domain` 供前端直連讀取。
