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

## Kotlin Multiplatform 客戶端

- `mail-kmp/composeApp/src/commonMain/kotlin/com/cloudmail/app`: 共用 DTO、Ktor adapter、加密 SQLDelight cache、同步 repository 與 StateFlow ViewModel。
- `presentation/`: Compose Material 3 自適應 UI；不要在 UI 發 HTTP 或執行 SQL。
- `androidMain`、`iosMain`、`desktopMain`: 平台金鑰儲存、HTML 安全 renderer、檔案、通知與背景同步；平台能力只能透過 common port 使用。
- `mail-kmp/iosApp`: SwiftUI host、Xcode shared scheme 與 iOS background task integration。
- `docs/kmp/api-contract.md`: 原生 `/api/app/v1` 契約；原有 Web API 格式與原始 JWT Authorization header 必須保持相容。
- `mail-worker/migrations/0001_app_v1.sql`: 加法式 D1 migration；trigger 在原有收信、Web 修改交易內記錄 sequence event。勿在 HTTP request 自動套用 migration。
- 共用與桌面測試：`cd mail-kmp && ./gradlew :composeApp:desktopTest`（Linux 需圖形顯示，例如 `xvfb-run -a`）。Android：`:composeApp:testDebugUnitTest :composeApp:lintDebug :composeApp:assembleDebug`。完整 JDK 21、Android SDK 35；Gradle Wrapper 與 Version Catalog 固定版本。
- macOS 驗證 iOS：`:composeApp:iosSimulatorArm64Test :composeApp:linkDebugFrameworkIosSimulatorArm64`，再使用 Xcode shared `CloudMail` scheme 建置。沒有 Xcode 時不得宣稱 iOS 已編譯或通過測試。
- Worker：`npm test`；Web：`npm test && npm run build`。Browser tests 使用 Chromium，可用 `CHROMIUM_PATH` 設定路徑。
- 不提交 build、APK、安裝包、`local.properties`、私密設定。依賴 checksum 變更需審查來源，不能以關閉 strict verification 解決 CI 失敗。
- 附件改由授權 `/api/oss/*` 或 `/api/app/v1/attachments/:id` 存取；Web 使用 authenticated Blob，不可將 Token 放入 URL 或送往信件提供的網域。公開 R2/S3 domain 需由操作者另行關閉附件公開存取。
- 新寄信必須先持久化 operation ID，未知結果先查詢相同 operation，不可盲目重試或產生新 ID；離線 queue 必須依持久化 sequence 重放。
- 所有郵件、草稿、操作與 cursor 在寫入 client cache 前加密；登出需取消與隔離背景工作、撤銷 session、清除本機敏感資料。診斷不可含 Token、密碼或郵件正文。
- 不操作正式 DNS/MX、D1/R2、Secrets、部署或對外測試寄信；測試只用隔離 fixture。部署與 migration 流程參閱 `docs/kmp/backend-migration.md` 與 release checklist。
