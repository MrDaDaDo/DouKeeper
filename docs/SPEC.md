# DouKeeper — 規格與規劃書

| 項目 | 內容 |
|---|---|
| 名稱 | **DouKeeper**（2026-10-13 定案，各語言都用英文名。原暫名 DouyinVideoDownloader；DouSave／抖存 已有同類專案使用，放棄） |
| Repository | `github.com/MrDaDaDo/DouKeeper` |
| Target Platform | Android Native (Kotlin) |
| Package | `io.github.mrdadado.doukeeper`（改名後**刻意不改**：改了手機會當成另一個 App，設定與紀錄都要重來） |
| Doc Version | **v0.2.0** |
| Last Updated | 2026-10-13 |
| 發布方式 | **自用 / 側載 APK，不上架 Google Play**（理由見 §1.3） |

---

## 版本變更摘要

### v0.1.0 → v0.2.0

- **解析方式以實測改寫**（§5.B）：網路上流傳的 `_ROUTER_DATA` + `playwm→play` 已經失效，
  改成 WebView 載入網頁版、攔截 `aweme/detail`。
- **驗證版在模擬器上跑通**：手動通過一次人機驗證後，兩支影片（含 110MB 長片）都下載成功，
  第二支不再要求驗證。
- 使用者的三個決定（2026-10-13）：
  1. **人機驗證交給使用者手動完成，不嘗試繞過。** 原話：「我不想挑戰人機驗證，徒勞無功」。
     驗證是抖音和破解者之間的軍備競賽，自用工具沒有理由加入；滑一下的成本遠低於維護繞過方案。
  2. **先不做實機測試。** B2（抖音能否叫出系統分享選單）、B4 延後，剪貼簿入口照樣做當保險。
  3. **假設網路環境不影響能否通過驗證。** 第一次只看到逾時、沒看到驗證畫面，重試後才出現；
     如果日後證明跟網路環境有關，那是使用環境的前提，不改架構。

### v0.1.0（初版）

- 依使用者需求建立規格：在抖音（中國版）按「分享」時，系統分享選單出現本 App；
  選了之後讓使用者挑要存什麼，然後下載到手機。
- 使用者確認的範圍：
  - 發布方式：自用 / 側載。
  - 影片預設**無浮水印**，但**每次都可以選**：無浮水印影片 / 有浮水印影片 / 只要音軌 / 圖片。
  - 支援圖文（相簿）作品、下載紀錄頁、背景音樂單獨存。
- **還沒有任何一條解析路徑經過實測。** §5.B 寫的是目前已知的做法，Phase 0 的第一件事
  就是用實機分享出來的連結驗證它；驗證前不把它當成事實。

---

## 1. 決策紀錄

### 1.1 已定案

| 決策 | 選定方案 | 白話說明 |
|---|---|---|
| 平台 | Android 原生 Kotlin | 工具鏈與建置環境已在本機實測可用 |
| 發布方式 | **側載 APK** | 不上 Play。產物放 `dist/`，傳到手機安裝 |
| 進入點 | **剪貼簿**：抖音「複製連結」→ 打開 App 自動讀取，或下拉通知欄點「DouKeeper」磚塊 | 抖音的分享面板叫不出系統分享選單（B2 實測）。磚塊只負責把 App 叫到前面，剪貼簿由 App 拿到焦點後讀——背景服務讀不到剪貼簿 |
| 其他進入點 | 系統分享選單（`ACTION_SEND`）、App 內手動貼上 | 分享選單留著給其他 App 或抖音日後改版；同一個連結只自動處理一次 |
| 穩定性判斷 | App 記錄每次成敗（`files/attempts.tsv`），畫面顯示最近 50 次成功率 | 失敗時好時壞，密集測試本身會觸發風控，只能靠日常使用累積判斷 |
| 下載內容 | 每次彈出選單讓使用者勾選 | 可複選：無浮水印影片、有浮水印影片、音軌、圖片（圖文作品才出現） |
| 預設勾選 | 無浮水印影片（圖文作品則為全部圖片） | 可在設定改成「記住上次的選擇」或「不再詢問，直接下載」 |
| 下載執行 | **`DownloadCenter`（process 層級佇列）＋ `DownloadService`（前景服務，dataSync）** | 解析留在畫面（需要 WebView、可能要人機驗證）；下載交給背景，切到別的 App 也繼續，通知欄顯示進度，完成後通知點一下開啟。沒用 WorkManager：只需要「撐住 process＋顯示進度」，不需要排程與重開機後續傳 |
| 存放位置 | MediaStore：`Movies/DouKeeper`、`Pictures/DouKeeper`、`Music/DouKeeper` | 相簿與音樂 App 直接看得到，不需要儲存權限（§5.D） |
| 音軌 | 從影片**重新封裝**出音訊為 `.m4a`，不轉檔 | 用 `MediaExtractor` + `MediaMuxer`，不引入 FFmpeg（§5.E） |
| 背景音樂 | 另外下載作品標示的配樂網址（通常是 `.mp3`） | 與「音軌」不同：配樂是原曲，音軌是影片裡實際的聲音（含人聲） |
| 下載紀錄 | Room 資料庫 | 可重開、分享、重新下載、刪除紀錄 |
| UI | Jetpack Compose + Material 3，跟隨系統深色模式 | — |
| DI | Hilt（KSP） | — |
| 解析 | **隱藏 WebView 載入網頁版，攔截 `aweme/detail`** | 純 HTTP 已被抖音擋住（§5.B 實測） |
| 人機驗證 | **出現時把 WebView 顯示給使用者手動完成，不寫任何繞過** | 使用者決定。通過一次後 cookie 保留，後續不再要求（§5.B 實測） |
| 網路 | OkHttp | 下載檔案與短網址導向；需要自己控制 User-Agent 與重新導向 |
| JSON | kotlinx.serialization（`JsonElement` 走訪） | 抖音的資料結構很大又常變，只取需要的欄位，不建完整資料類別 |
| minSdk / targetSdk | **29** / 36 | 29（Android 10）起 MediaStore 可以寫入公用目錄而不需要儲存權限；自用專案不需要往下相容 |
| 語言 | **English（預設）、繁體中文、简体中文** | `values/` 放英文當所有未匹配語言的歸宿，`values-b+zh+Hant/`（台港澳）、`values-b+zh+Hans/`（中新）。`locales_config.xml` 讓 Android 13+ 可在系統設定單獨切換。存放資料夾固定用英文 `DouKeeper`（跟 App 名稱一致），不跟著語言變，免得切換語言後檔案散在兩處；0.3.0 以前「抖音下載」、0.4–0.6 是 `DouyinVideoDownloader`；`attempts.tsv` 的失敗原因固定英文，方便統計 |
| 簽章 | **仍然要產生自己的金鑰**（§9） | 側載也一樣：簽章不同就不能覆蓋安裝更新 |

### 1.2 待補資訊

| # | 項目 | 需要時機 | 狀態 |
|---|---|---|---|
| B1 | App 中文名稱與最終 package name | Phase 0 | ⏳ package 一旦有人安裝就不要再改，否則會變成另一個 App |
| B2 | 抖音分享面板能否叫出系統分享選單（實機） | Phase 0 | ✗ **不能**（2026-10-13，Android 17 實機）：抖音的分享面板是自己畫的，沒有本 App 可選。R1 成真，主要進入點改為剪貼簿（§13 R1） |
| B3 | §5.B 解析路徑實測 | Phase 0 | 🟡 一般影片、長影片 ✅；**圖文待補**（需要一段圖文作品的分享文字） |
| B4 | 測試手機型號 / Android 版本 | 延後 | ⏸ 目前只在模擬器（Android 14 / WebView 146）驗證 |
| B5 | 簽章金鑰 | Phase 0 | ✅ 2026-10-13 產生，見 §9 |

### 1.3 為什麼不上架 Google Play

Play 的政策禁止 App 讓使用者未經授權下載第三方平台的內容，下載器類的 App 會被拒或事後下架。
自用不需要承擔這個風險，也省掉隱私權政策、資料安全表單、測試者招募這整條流程。

代價：沒有自動更新。用 §9 的固定金鑰簽章，新版 APK 才能直接覆蓋安裝。

---

## 2. 使用流程

```
抖音影片 → 分享 → （系統分享選單）選本 App
   → 半透明底部選單浮在抖音上方
       ├─ 解析中…（顯示作品標題、作者、封面）
       ├─ 勾選：☑ 影片（無浮水印） ☐ 影片（有浮水印） ☐ 音軌 ☐ 背景音樂 ☐ 圖片
       └─ [下載]
   → 選單關閉，回到抖音
   → 通知欄顯示進度 → 完成後通知可點開檔案
```

- 選單是透明主題的 Activity（`Theme.Translucent` 風格 + Compose `ModalBottomSheet`），
  看起來像浮在抖音上，不切換到完整 App 畫面。
- 解析失敗時選單直接顯示原因（§5.F），並提供「複製錯誤資訊」，不要只吐一個 Toast 就消失。
- 圖文作品沒有影片，選單只列「圖片」「背景音樂」。
- 同一個作品同一種內容已經下載過 → 選項旁標示「已下載」，仍可再下載一次。

---

## 3. 系統架構與目錄結構

```
app/src/main/java/io/github/mrdadado/doukeeper/
├── share/        ShareReceiverActivity（底部選單）、ShareTextParser（純函式）
├── parse/        DouyinResolver（短網址 → 作品 ID → 詳細資料）、AwemeParser（純函式）
├── download/     DownloadWorker、MediaStoreWriter、AudioExtractor
├── history/      Room：DownloadRecord、DownloadDao
├── ui/           MainActivity（下載紀錄 + 手動貼上）、SettingsScreen、theme/
└── di/           Hilt 模組
```

原則：**能抽成純函式的就抽出來寫單元測試**。網路與 Android API 只在邊界，
「從分享文字找出網址」「從頁面 JSON 取出各種下載網址」「產生檔名」都不碰 Android。

---

## 4. 資料模型

```kotlin
data class Aweme(
    val id: String,               // 作品 ID（aweme_id）
    val title: String,            // 作品描述
    val author: String,           // 作者暱稱
    val coverUrl: String?,
    val kind: Kind,               // VIDEO / IMAGES
    val videoNoWatermark: String?,
    val videoWatermark: String?,
    val musicUrl: String?,
    val musicTitle: String?,
    val images: List<String>,     // 圖文作品才有
)
```

下載紀錄（Room）：`awemeId`、`kind`（VIDEO_NWM / VIDEO_WM / AUDIO / MUSIC / IMAGE）、
`title`、`author`、`contentUri`、`createdAt`、`status`、`errorMessage`。

---

## 5. 核心技術規格

### 5.A 從分享文字取出網址

抖音分享出來的是一段混合文字，大致像這樣（實際格式以 B3 實測為準）：

```
1.23 abc:/ 標題文字 #話題 https://v.douyin.com/xxxxxxx/ 复制此链接，打开Dou音搜索，直接观看视频！
```

- `ShareTextParser.extractUrl(text)`：找出第一個 `douyin.com` / `iesdouyin.com` 網址。
- 剪貼簿入口也走同一個函式。
- 測試：至少涵蓋前後有中文標點、網址在中間、網址後面緊接中文、只有網址、完全沒有網址。

### 5.B 解析作品資料（2026-10-13 桌面實測，Android WebView 待驗）

#### 舊做法已經失效

網路上流傳的做法是「請求 `iesdouyin.com/share/video/{id}/`，從 `window._ROUTER_DATA` 取資料，
把 `playwm` 換成 `play`」。實測：

- 短網址導向**仍然正常**：`v.douyin.com/xxx/` → 302 → `www.iesdouyin.com/share/video/{id}/?region=XX&…`。
- 但分享頁的 `_ROUTER_DATA` 裡**已經沒有作品資料**，只剩 UA、query 等外殼。
  在瀏覽器裡執行完 JS，頁面顯示「抱歉出错了 / 请尝试在抖音内观看」。
- 直接 HTTP 請求網頁版 `www.douyin.com/video/{id}` 只拿到反爬蟲的 JS 驗證頁（`__ac_nonce`、`byted_acrawler`）。
- 外部佐證：jiji262/douyin-downloader（1.2 萬星）README 寫明抖音的請求驗證目前擋住 CLI 下載單支作品；
  Lin6932271 的 Android 專案（2026-10-04）也記錄「普通分享頁缺少播放地址，匿名直調詳情接口返回空響應」。

**結論：純 HTTP 走不通，必須讓真正的瀏覽器引擎執行抖音的網頁。**

#### 現行做法：隱藏 WebView 攔截 `aweme/detail`

1. 短網址跟隨導向，從最終網址取出作品 ID（`/video/{id}`，圖文推測是 `/note/{id}`，待驗）。
2. 隱藏 WebView 以**桌面版 User-Agent** 載入 `https://www.douyin.com/video/{id}`。
   - ⚠️ 手機 UA 會被導到 `m.douyin.com/share/video/{id}`，**不會**發出 detail 請求。
3. 頁面自己的 JS 會呼叫 `/aweme/v1/web/aweme/detail/`（簽章、cookie 都由它自己處理）。
   用 document-start 腳本（`WebViewCompat.addDocumentStartJavaScript`）包住 `fetch`/`XMLHttpRequest`，
   把那支回應的內容交給 Kotlin。
4. **必須確認回應裡的 `aweme_id` 等於要求的 ID**：6 次實測裡有 1 次頁面被導到
   `/jingxuan?modal_id={別支影片}`，不檢查就會下載到別人的作品。

`aweme_detail` 裡可用的欄位（實測）：

| 欄位 | 內容 |
|---|---|
| `video.play_addr.url_list` | **無浮水印**，H.264（此例 1920×1080） |
| `video.download_addr.url_list` | **有浮水印**（此例 1280×720，左上角「抖音 / 抖音号 / 作者名」，片尾多約 3 秒抖音片尾） |
| `video.bit_rate[]` | 多種畫質，含 H.265 原始解析度（此例 2560×1440）；`is_h265` 標示編碼 |
| `music.play_url.url_list` | 配樂 `.mp3` |
| `desc`、`author.nickname`、`video.duration`（毫秒） | 標題、作者、長度 |
| `images` | 圖文作品才有，**待驗** |

`AwemeParser` 只吃 detail JSON 字串、吐 `Aweme`，測試用存下來的真實回應當夾具
（`app/src/test/resources/fixtures/`）。抖音改版時，先存一份新回應、讓測試紅，再修。

#### 下載實測

| 檔案 | 不帶 cookie / Referer | 結果 |
|---|---|---|
| 無浮水印 1080p H.264 | ✅ 200 | 1.6MB，可完整解碼，畫面無浮水印 |
| 無浮水印 1440p H.265 | ✅ 200 | 1.9MB |
| 有浮水印 | ✅ 200 | 2.4MB |
| `www.douyin.com/aweme/v1/play/?…`（`url_list` 的備用網址） | ✅ 200 | 與第一個網址同一檔案 |
| 配樂 | ✅ 200 | 146KB MP3 |
| 音軌（`ffmpeg -c:a copy` 重新封裝） | ✅ | AAC，不需轉檔，證實 §5.E 可行 |

- **下載只需要 User-Agent**，不需要 cookie 或 Referer。驗證只擋在「取得資料」那一步。
- 網址有效期約 **3 小時**（路徑裡的十六進位時間戳 − `dy_q` = 10809 秒）。
  解析完立刻下載沒問題，但**下載紀錄不能存網址**，要重新下載就重新解析。
- `url_list` 有多個 CDN 網址，第一個失敗就換下一個。

#### 穩定性（桌面 Chromium，同一支影片，每次全新環境）

- 6 次中 5 次成功，取得資料耗時 3.2–5.8 秒；失敗那次是上述導到別支影片。→ 失敗時自動重試一次。
- 未遇到驗證碼。

#### Android WebView 實測（2026-10-13，模擬器 Android 14 / WebView 146）

- 短網址 → 作品 ID：✅ 789ms。
- 第一次載入：兩次各等 25 秒都**逾時**，沒有攔到 detail，WebView 畫面空白（當時還沒有診斷記錄）。
- 加上診斷記錄後重跑：**頁面跳出人機驗證**（使用者在模擬器畫面上看到）。
- 頁面上的 JS 錯誤不是原因：一個是抖音自己的 CSP report-only 警告，另一大串是
  `chrome-extension://…/icon.png` 讀取失敗——那是抖音的指紋腳本在探測瀏覽器擴充套件。
  它說明抖音在對這個環境做風控評分；我們自稱 Windows Chrome、實際是 Linux ARM + 觸控 + 模擬器 GPU，
  這種矛盾很可能就是被要求驗證的原因。
- 使用者重試，在放大的 WebView 裡**手動完成滑塊驗證** → 拿到 detail，
  `作者_7600000000000000123.mp4` 存進 `Movies/抖音下載`，1,641,422 bytes，與桌面 curl 下載的完全相同。
- **緊接著分享第二支沒解析過的影片：沒有再要求驗證**，直接下載完成。
  那支是 8 分 37 秒的長影片（1440×1080 H.264，110MB），拉回電腦完整解碼無錯誤。

**結論：可行，但人機驗證是常態而不是例外。** 正式版必須：
1. 偵測到驗證畫面時把 WebView 顯示給使用者操作（不能只在背景跑）。
2. 保留 WebView 的 cookie——通過一次之後的請求不再要求驗證。

**實體手機（Android 17，2026-10-13）：WebView 放進 Compose 畫面時整個 App 全黑。**
版面與無障礙節點都正常、沒有當機，就是一個像素都畫不出來；拿掉 WebView 立刻正常。
WebView 改成 `LAYER_TYPE_SOFTWARE` 後正常顯示。模擬器（Android 14）沒有這個問題——
**模擬器測過不代表手機沒事**，畫面相關的改動都要上實機看一次。

**實體手機抓不到資料（2026-10-13，透過 WebView 遠端偵錯 / CDP 實測）：**

| 嘗試 | 結果 |
|---|---|
| 載入 `/video/{id}`，JS 包 fetch/XHR | 頁面**有**發出正確 ID 的 detail（XHR），但頁面跑完後 `window.fetch` 已變回原生——攔截被抖音的安全 SDK 換掉或繞過 |
| 載入 `jingxuan?modal_id={id}` | 攔截還在，但 15 秒內不發 detail 請求 |
| 在原生層看到 detail 請求後，帶 WebView 的 33 個 cookie 重送 | `403 Blocked by ArgusSecurityPlugin Sign Invalid`——簽章綁定原請求，重送無效 |
| 只讀頁面上 `<video>` 的網址（完全被動） | 頁面被導到 `jingxuan?previous_page=web_video_404_link&modal_id={別支}`，沒有影片元素 |
| 使用者手動在 App 裡貼上連結 | 頁面被導到**之前看過的另一支影片**，等不到正確資料 |

多次測試後，抖音開始把 `/video/{id}` 當成 404 導到推薦影片，風控明顯在升級。
再往下只剩「破解簽章」或「跟安全 SDK 比誰先包 XHR」，都屬於使用者已經拒絕的軍備競賽。

**依使用者訂的標準（不能穩定下載就不開發），一度判定為不穩定。**

但約 5 分鐘後使用者自己在手機上正常操作（貼上連結、按下載）：**成功**。
無浮水印 1080×1920 7.2 秒畫面乾淨；有浮水印 720×1280 10.1 秒，左上角有抖音標誌與抖音號、片尾多 3 秒。
→ 失敗是**時好時壞**，而且很可能是我們自己用 CDP 在幾分鐘內反覆導頁觸發的風控。
「一次成功」不能證明穩定，改成讓使用者實際使用幾天、由 App 記錄成功率再判斷（§7）。
**不要再用 CDP 密集測試抖音頁面**——那本身就會污染結果。

仍待驗：通過一次能撐多久（幾小時？幾天？）、實體手機是否同樣需要驗證、換網路環境時是否也能通過。

#### 仍未驗證

- 驗證通過後 cookie 的有效期，以及實體手機上的表現（見上）。
- 圖文作品、長影片、私密/已刪除作品。
- 連續使用多天、短時間大量解析是否會觸發驗證碼。

### 5.C 下載

- `DownloadWorker`（CoroutineWorker，`setForeground` 顯示進度通知）。
- 一次分享勾選多項 → 一個 Work 依序下載，通知顯示「2/5」。
- OkHttp 串流寫入，邊下邊更新進度；**先寫到 MediaStore 的 `IS_PENDING=1` 項目，
  完成後才設成 0**——中途失敗不會在相簿留下壞檔。失敗時刪除該項目。
- 檔名：`{作者}_{作品ID}[_wm|_audio|_music|_01].{副檔名}`，
  去掉檔名不允許的字元，長度上限 80 字（`FileNames` 純函式，有測試）。
- 網路錯誤自動重試（WorkManager 指數退避，最多 3 次）。

### 5.D 儲存與權限

| 權限 | 用途 | 備註 |
|---|---|---|
| `INTERNET` | 解析與下載 | |
| `POST_NOTIFICATIONS` | 下載進度通知 | Android 13+ 需要執行時詢問。拒絕的話下載仍會進行，只是看不到進度 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | WorkManager 前景下載 | Android 14 起前景服務必須宣告類型 |

**不需要**任何儲存權限：minSdk 29 起，透過 MediaStore 寫入 App 自己建立的檔案不需要權限。

### 5.E 音軌

- 先下載無浮水印影片到 App 的快取目錄，用 `MediaExtractor` 找出音訊軌，
  `MediaMuxer` 原封不動重新封裝成 `.m4a`，再寫入 `Music/DouKeeper`。
- **不轉檔**：抖音的音訊軌通常是 AAC，直接封裝不會損失音質，也不用 FFmpeg（APK 會大幾十 MB）。
- 若同時勾了影片，就重用剛下載好的影片，不要再下載一次。
- 音軌不是 AAC 的話（實測後確認是否會發生）→ 顯示錯誤，不默默輸出壞檔。

### 5.F 錯誤處理

每一種失敗都要讓使用者知道是哪一種，不要全部變成「下載失敗」：

| 情況 | 顯示 |
|---|---|
| 分享文字裡找不到網址 | 「這段分享內容裡沒有抖音連結」 |
| 短網址導向失敗 / 無網路 | 「連不到抖音，請檢查網路」 |
| 頁面裡找不到資料 | 「抖音頁面格式可能改了」＋ 複製診斷資訊（網址、HTTP 狀態、頁面前 500 字） |
| 作品已刪除 / 私密 | 「作品不存在或無法公開觀看」 |
| 下載中途斷線 | 自動重試，3 次後通知「下載失敗，點此重試」 |

---

## 6. 設定頁

主畫面右上角 ⚙ 進入。項目少，存在 SharedPreferences（`Settings.kt`）。

| 設定 | 預設 | 說明 |
|---|---|---|
| 自動開始 | 開 | 打開 App 時剪貼簿有新的抖音連結就直接開始；關掉的話只貼進輸入框 |
| 預設下載內容 | 無浮水印影片 | 可複選無浮水印／有浮水印／配樂。圖文作品一律下載圖片，有勾配樂再加上配樂。至少留一項 |
| 語言 | 跟隨系統 | Android 13+ 直接跳系統的「App 語言」頁，不自己另做一套 |
| 重設成功率統計 | — | 清掉 `attempts.tsv` |

原規劃的「分享後每次詢問／記住上次」已不適用：入口變成剪貼簿後，詢問會多一步，
而「直接下載預設內容，其他項目在作品卡上補按」已經涵蓋同樣需求。

---|---|---|
| 分享後的行為 | 每次詢問 | 每次詢問 / 記住上次的選擇並詢問 / 直接用預設下載 |
| 預設下載內容 | 無浮水印影片 | 「直接下載」時使用 |
| 完成後通知 | 開 | |

---

## 7. 測試策略

| 層級 | 範圍 |
|---|---|
| 單元測試 | `ShareTextParser`、`AwemeParser`（真實頁面夾具）、浮水印網址轉換、`FileNames` |
| 整合測試 | `DouyinResolver` 用 OkHttp `MockWebServer` 模擬重新導向與頁面 |
| 手動實測 | 一般影片 / 圖文 / 長影片 / 已刪除作品；各勾選組合；下載中關掉網路；下載中滑掉 App |
| 產物驗證 | 下載的無浮水印影片實際播放確認沒有浮水印；`.m4a` 能在音樂 App 播放 |

---

## 8. CI

GitHub Actions：`assembleDebug` + `testDebugUnitTest`。沿用先前的專案的 `ci.yml`
（JDK 21、node24 版本的 actions、不使用 `setup-android`）。

**CI 不打真實的抖音網路**：外部網站會變、會擋 CI 的 IP，結果不穩定的測試比沒有測試更糟。
線上驗證留給手動實測與 `tools/` 裡的腳本。

---

## 9. 簽章與發布

- 側載也要固定金鑰：debug 簽章每台電腦不同，換台電腦建置的 APK 會**無法覆蓋安裝**，
  只能先移除（下載紀錄跟著消失）。
- **金鑰已產生（2026-10-13）**：PKCS12、RSA 4096、alias `doukeeper`，憑證 SHA-256 `65004262…0c796df1`。
  金鑰檔與密碼放在使用者層級的 `~/.gradle/gradle.properties`（`DOUKEEPER_*`），不在 repo 裡；
  完整步驟、備份與換電腦方式見 `docs/RELEASE.md`。
- `./gradlew assembleRelease` 之後自動複製到 `dist/DouKeeper-v{版本}.apk`（`exportReleaseApk`）。
  沒設定簽章參數時這個任務直接失敗，避免散佈沒簽章的檔案。
- 不開 R8：WebView 注入的 JS 與 `WebMessageListener` 靠名稱對接，混淆出問題只會在實機上發現。
- 0.7.1 起 GitHub Release 改附 release 版。從 debug 版換過來必須先移除一次（設定與統計會清掉）。
- 版本號：semver `versionName` + 遞增的 `versionCode`。

---

## 10. Phase 任務清單與驗收標準

### Phase 0 — 骨架與實測

| 任務 | 驗收標準 | 狀態 |
|---|---|---|
| Gradle KTS + Version Catalog + Compose 骨架 | `assembleDebug` 通過，App 可安裝啟動 | ✅ Hilt 延到正式版再加 |
| 註冊 `ACTION_SEND` | 實機在抖音按分享，**系統選單中出現本 App** | 🟡 模擬器用 `adb am start` 送分享 intent 可正常進入；抖音實機延後（B2） |
| 收集真實分享文字與頁面 | 至少：一般影片 ×2、圖文 ×1、長影片 ×1 | 🟡 一般影片 ×2（其中一支長片）；圖文待補 |
| §5.B 解析路徑實測 | WebView 取得 detail 並下載成功 | ✅ 模擬器：手動驗證一次後連續兩支成功 |
| 簽章金鑰 + CI | release APK 已簽章；CI 綠燈 | 🟡 release APK 已用固定金鑰簽章（apksigner 驗證通過）；CI 未做 |

### Phase 1 — 解析

| 任務 | 驗收標準 | 狀態 |
|---|---|---|
| `ShareTextParser` | 單元測試涵蓋 §5.A 列出的情況 | ⏳ |
| `DouyinResolver` + `AwemeParser` | 所有夾具解析出正確的 ID、標題、作者、各種網址 | ⏳ |
| 底部選單顯示作品資訊與選項 | 分享後 3 秒內顯示標題與封面 | ⏳ |

### Phase 2 — 下載

| 任務 | 驗收標準 | 狀態 |
|---|---|---|
| 無浮水印 / 有浮水印影片下載 | 相簿看得到；無浮水印版實際播放確認無浮水印 | ⏳ |
| 前景通知與進度 | 下載中滑掉 App，下載照樣完成 | ⏳ |
| 中途失敗不留壞檔 | 下載到一半關網路，相簿裡沒有殘缺檔案 | ⏳ |
| 圖文作品圖片 | 全部圖片依序存入 `Pictures/DouKeeper` | ⏳ |

### Phase 3 — 音訊

| 任務 | 驗收標準 | 狀態 |
|---|---|---|
| 音軌（`.m4a` 重新封裝） | 音樂 App 可播放，長度與影片一致 | ⏳ |
| 背景音樂 | 存入 `Music/DouKeeper` | ⏳ |
| 同時勾影片與音軌只下載一次影片 | 網路流量只有一份影片大小 | ⏳ |

### Phase 4 — 紀錄與設定

| 任務 | 驗收標準 | 狀態 |
|---|---|---|
| 下載紀錄頁 | 列出縮圖、標題、類型、時間；可開啟、分享、刪除紀錄 | ⏳ |
| 手動貼上 / 剪貼簿入口 | 貼上分享文字可以走完同樣流程 | ⏳ |
| 設定頁（§6） | 三種「分享後的行為」皆正確運作 | ⏳ |
| 已下載標示 | 同一作品再分享時選項顯示「已下載」 | ⏳ |

---

## 11. 風險清單

| # | 風險 | 影響 | 對策 |
|---|---|---|---|
| R1 | 抖音的分享面板不叫出系統分享選單，只有「複製連結」 | 主要進入點失效 | 剪貼簿入口（App 開啟時偵測剪貼簿裡的抖音連結，詢問是否下載） |
| R2 | 抖音改版，頁面結構或 JSON 欄位改變 | 解析全部失敗 | 解析集中在 `AwemeParser`，有夾具測試；錯誤訊息帶診斷資訊方便修 |
| R3 | 抖音加上簽章 / cookie 驗證 | 拿不到資料 | Phase 0 實測確認；若需要，評估改用 WebView 載入頁面取資料 |
| R4 | 無浮水印網址做法失效 | 只剩有浮水印版本 | 選單中標示「無浮水印目前無法取得」，有浮水印版本仍可下載 |
| R5 | 著作權 | — | 自用工具，下載內容的使用由使用者自行負責；不做批次下載、不做帳號整頁下載 |

---

## 12. 建置環境

沿用先前的專案`docs/SPEC.md` §16 已實測可用的版本：

| 項目 | 版本 | 備註 |
|---|---|---|
| **JDK** | **21** | ⚠️ 不要用 Android Studio 內建的 JBR（JDK 25），Gradle 8.14 不支援 |
| Gradle | 8.14.3（wrapper） | |
| AGP | 8.13.0 | |
| Kotlin | 2.2.20 | |
| KSP | 2.2.20-2.0.3 | 前半段必須與 Kotlin 完全一致 |
| Hilt | 2.56.2 | 走 KSP |
| Compose BOM | 2025.04.01 | |
| compileSdk / targetSdk / minSdk | 36 / 36 / **29** | minSdk 與先前的專案不同，見 §1.1 |

另外需要（版本在 Phase 0 建置時確定並回填）：OkHttp、kotlinx.serialization、Room、WorkManager。
