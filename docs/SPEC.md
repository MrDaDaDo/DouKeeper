# 抖音下載器 — 規格與規劃書

| 項目 | 內容 |
|---|---|
| 暫定名稱 | DouyinVideoDownloader（中文名待定） |
| Target Platform | Android Native (Kotlin) |
| Package | `io.github.mrdadado.doukeeper`（暫定，見 §1.2 B1） |
| Doc Version | **v0.1.0** |
| Last Updated | 2026-10-13 |
| 發布方式 | **自用 / 側載 APK，不上架 Google Play**（理由見 §1.3） |

---

## 版本變更摘要

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
| 進入點 | 系統分享選單（`ACTION_SEND` / `text/plain`） | 抖音分享出來的是一段文字，裡面夾著短網址 |
| 備援進入點 | App 內貼上連結 / 讀剪貼簿 | 抖音的分享面板不一定會叫出系統分享選單（§13 R1），不能只押一條路 |
| 下載內容 | 每次彈出選單讓使用者勾選 | 可複選：無浮水印影片、有浮水印影片、音軌、圖片（圖文作品才出現） |
| 預設勾選 | 無浮水印影片（圖文作品則為全部圖片） | 可在設定改成「記住上次的選擇」或「不再詢問，直接下載」 |
| 下載執行 | WorkManager + 前景通知 | 選完就可以回抖音繼續滑，進度在通知欄 |
| 存放位置 | MediaStore：`Movies/抖音下載`、`Pictures/抖音下載`、`Music/抖音下載` | 相簿與音樂 App 直接看得到，不需要儲存權限（§5.D） |
| 音軌 | 從影片**重新封裝**出音訊為 `.m4a`，不轉檔 | 用 `MediaExtractor` + `MediaMuxer`，不引入 FFmpeg（§5.E） |
| 背景音樂 | 另外下載作品標示的配樂網址（通常是 `.mp3`） | 與「音軌」不同：配樂是原曲，音軌是影片裡實際的聲音（含人聲） |
| 下載紀錄 | Room 資料庫 | 可重開、分享、重新下載、刪除紀錄 |
| UI | Jetpack Compose + Material 3，跟隨系統深色模式 | — |
| DI | Hilt（KSP） | — |
| 解析 | **隱藏 WebView 載入網頁版，攔截 `aweme/detail`** | 純 HTTP 已被抖音擋住（§5.B 實測） |
| 網路 | OkHttp | 下載檔案與短網址導向；需要自己控制 User-Agent 與重新導向 |
| JSON | kotlinx.serialization（`JsonElement` 走訪） | 抖音的資料結構很大又常變，只取需要的欄位，不建完整資料類別 |
| minSdk / targetSdk | **29** / 36 | 29（Android 10）起 MediaStore 可以寫入公用目錄而不需要儲存權限；自用專案不需要往下相容 |
| 語言 | 繁體中文 | 自用。字串仍一律放 `strings.xml`，不寫死 |
| 簽章 | **仍然要產生自己的金鑰**（§9） | 側載也一樣：簽章不同就不能覆蓋安裝更新 |

### 1.2 待補資訊

| # | 項目 | 需要時機 | 狀態 |
|---|---|---|---|
| B1 | App 中文名稱與最終 package name | Phase 0 | ⏳ package 一旦有人安裝就不要再改，否則會變成另一個 App |
| B2 | 抖音分享面板能否叫出系統分享選單（實機） | **Phase 0** | ⏳ 決定主要進入點是分享還是剪貼簿 |
| B3 | §5.B 解析路徑實測 | **Phase 0** | ⏳ 需要幾個真實分享文字：一般影片、圖文、長影片 |
| B4 | 測試手機型號 / Android 版本 | Phase 0 | ⏳ |
| B5 | 簽章金鑰 | Phase 0 | ⏳ |

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

#### 仍未驗證

- **Android WebView 的實際行為**（以上全部是桌面 Chromium）。這是 Phase 0 最重要的一項。
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
  `MediaMuxer` 原封不動重新封裝成 `.m4a`，再寫入 `Music/抖音下載`。
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

| 設定 | 預設 | 說明 |
|---|---|---|
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
- 產生方式與先前的專案`docs/RELEASE.md` 步驟 1 相同；`*.jks`、`keystore.properties` 不進版控。
- `./gradlew assembleRelease` 之後複製到 `dist/DouyinDL-v{版本}.apk`（比照先前的專案的 `exportDebugApk`）。
- 版本號：semver `versionName` + 遞增的 `versionCode`。

---

## 10. Phase 任務清單與驗收標準

### Phase 0 — 骨架與實測

| 任務 | 驗收標準 | 狀態 |
|---|---|---|
| Gradle KTS + Version Catalog + Hilt + Compose 骨架 | `assembleDebug` 通過，空白 App 可安裝啟動 | ⏳ |
| `ShareReceiverActivity` 註冊 `ACTION_SEND` | 實機在抖音按分享，**系統選單中出現本 App**，點進來能顯示收到的文字 | ⏳ 結果回填 B2 |
| 收集真實分享文字與頁面 | 至少：一般影片 ×2、圖文 ×1、長影片 ×1，存成測試夾具 | ⏳ |
| §5.B 解析路徑實測 | 四個「不能確定的事」都有答案並回填 §5.B | ⏳ |
| 簽章金鑰 + CI | release APK 已簽章；CI 綠燈 | ⏳ |

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
| 圖文作品圖片 | 全部圖片依序存入 `Pictures/抖音下載` | ⏳ |

### Phase 3 — 音訊

| 任務 | 驗收標準 | 狀態 |
|---|---|---|
| 音軌（`.m4a` 重新封裝） | 音樂 App 可播放，長度與影片一致 | ⏳ |
| 背景音樂 | 存入 `Music/抖音下載` | ⏳ |
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
