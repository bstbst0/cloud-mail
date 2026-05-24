<p align="center">
    <img src="doc/demo/logo.png" width="80px" />
    <h1 align="center">Cloud Mail</h1>
    <p align="center">基於 Cloudflare 的簡潔響應式郵件服務，支援郵件寄送與附件收發</p> 
    <p align="center">
        繁體中文 | <a href="/README-en.md" style="margin-left: 5px">English </a>
    </p>
    <p align="center">
        <a href="https://github.com/maillab/cloud-mail/tree/main?tab=MIT-1-ov-file" target="_blank" >
            <img src="https://img.shields.io/badge/license-MIT-green" />
        </a>    
        <a href="https://github.com/maillab/cloud-mail/releases" target="_blank" >
            <img src="https://img.shields.io/github/v/release/maillab/cloud-mail" alt="releases" />
        </a>  
        <a href="https://github.com/maillab/cloud-mail/issues" >
            <img src="https://img.shields.io/github/issues/maillab/cloud-mail" alt="issues" />
        </a>  
        <a href="https://github.com/maillab/cloud-mail/stargazers" target="_blank">
            <img src="https://img.shields.io/github/stars/maillab/cloud-mail" alt="stargazers" />
        </a>  
        <a href="https://github.com/maillab/cloud-mail/forks" target="_blank" >
            <img src="https://img.shields.io/github/forks/maillab/cloud-mail" alt="forks" />
        </a>
    </p>
    <p align="center">
        <a href="https://trendshift.io/repositories/14418" target="_blank" >
            <img src="https://trendshift.io/api/badge/repositories/14418" alt="trendshift" >
        </a>
    </p>
</p>


## 專案簡介

只需要一個網域名稱，就可以建立多個不同的電子郵件帳號，類似各大郵件服務平台。本專案支援部署到 Cloudflare Workers，以降低伺服器成本並建立自己的郵件服務。


## 專案展示

- [線上展示](https://skymail.ink)<br>
- [部署文件](https://doc.skymail.ink)<br>

| ![](/doc/demo/demo1.png) | ![](/doc/demo/demo2.png) |
|-----------------------|-----------------------|
| ![](/doc/demo/demo3.png) | ![](/doc/demo/demo4.png) |


## 功能介紹

- **💰 低成本使用**：可部署到 Cloudflare Workers，大幅降低伺服器成本

- **💻 響應式設計**：自動適配 PC 與大多數手機瀏覽器

- **📧 郵件寄送**：整合 Resend 發送郵件，支援群發、內嵌圖片與附件寄送，並可查看發送狀態

- **🛡️ 管理員功能**：可管理使用者與郵件，透過 RBAC 權限控制功能與資源使用

- **📦 附件收發**：支援郵件附件收發，使用 R2 物件儲存下載與保存檔案

- **🔔 郵件推播**：接收郵件後可轉發至 Telegram 機器人或其他郵件服務

- **📡 開放 API**：支援透過 API 批次建立使用者與多條件查詢郵件

- **📈 資料視覺化**：使用 ECharts 顯示系統資料與郵件成長趨勢

- **🎨 個人化設定**：可自訂網站標題、登入背景與透明度

- **🤖 人機驗證**：整合 Cloudflare Turnstile 防止機器人批次註冊

- **📜 更多功能**：持續開發中...


## 技術棧

- **平台**：[Cloudflare Workers](https://developers.cloudflare.com/workers/)

- **Web 框架**：[Hono](https://hono.dev/)

- **ORM：**[Drizzle](https://orm.drizzle.team/)

- **前端框架**：[Vue3](https://vuejs.org/) 

- **UI 框架**：[Element Plus](https://element-plus.org/) 

- **郵件發送：** [Resend](https://resend.com/)

- **快取**：[Cloudflare KV](https://developers.cloudflare.com/kv/)

- **資料庫**：[Cloudflare D1](https://developers.cloudflare.com/d1/)

- **檔案儲存**：[Cloudflare R2](https://developers.cloudflare.com/r2/)


## 目錄結構

```
cloud-mail
├── mail-worker # Worker 後端專案
│ ├── src
│ │ ├── api # API 介面層
│ │ ├── const # 專案常數
│ │ ├── dao # 資料存取層
│ │ ├── email # 郵件接收與處理
│ │ ├── entity # 資料庫實體
│ │ ├── error # 自訂例外
│ │ ├── hono # Web 框架設定、攔截器、全域例外
│ │ ├── i18n # 多語系
│ │ ├── init # 資料庫與快取初始化
│ │ ├── model # 回應資料封裝
│ │ ├── security # 身分驗證與權限
│ │ ├── service # 業務服務層
│ │ ├── template # 郵件模板
│ │ ├── utils # 工具類
│ │ └── index.js # 入口檔案
│ ├── package.json # 專案依賴
│ └── wrangler.toml # Worker 設定
│
├── mail-vue # Vue 前端專案
│ ├── src
│ │ ├── axios # axios 設定
│ │ ├── components # 自訂元件
│ │ ├── echarts # ECharts 元件
│ │ ├── i18n # 多語系
│ │ ├── init # 初始化
│ │ ├── layout # 版面配置
│ │ ├── perm # 權限驗證
│ │ ├── request # API 請求
│ │ ├── router # 路由設定
│ │ ├── store # 全域狀態管理
│ │ ├── utils # 工具類
│ │ ├── views # 頁面元件
│ │ ├── app.vue # 入口元件
│ │ ├── main.js # 入口 JS
│ │ └── style.css # 全域 CSS
│ ├── package.json # 專案依賴
└── └── env.release # 專案設定
```



## 贊助

<a href="https://doc.skymail.ink/support.html" >
<img width="170px" src="./doc/images/support.png" alt="">
</a>


## 授權

本專案採用 [MIT](LICENSE) 授權條款  


## 交流

[Telegram](https://t.me/cloud_mail_tg)
