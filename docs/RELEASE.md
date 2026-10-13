# 發布流程

DouKeeper 不上架，靠側載 APK 更新。Android 只允許**同一把金鑰簽章**的新版覆蓋安裝，
所以正式版一律用固定的 release 金鑰簽章，不散佈 debug 版。

---

## 1. 金鑰（只做一次，2026-10-13 已完成）

| 項目 | 內容 |
|---|---|
| 金鑰檔 | `~/.android/keystores/doukeeper-release.jks`（PKCS12，RSA 4096，效期 100 年） |
| alias | `doukeeper` |
| 憑證 | `CN=MrDaDaDo` |
| 憑證 SHA-256 | `65004262fc5574b3e99a483a6ed5b72decfb6d00ff0e5cba2b6748b70c796df1` |
| 密碼 | 只存在 `~/.gradle/gradle.properties`，**不進 repo** |

建置腳本從使用者層級的 `~/.gradle/gradle.properties` 讀四個參數：

```properties
DOUKEEPER_STORE_FILE=<金鑰檔的絕對路徑，用正斜線>
DOUKEEPER_STORE_PASSWORD=<密碼>
DOUKEEPER_KEY_ALIAS=doukeeper
DOUKEEPER_KEY_PASSWORD=<密碼，PKCS12 與 store 密碼相同>
```

放在使用者層級而不是專案裡：專案資料夾整個刪掉、重新 clone，金鑰和密碼都還在；
也不可能不小心 commit 進去（`.gitignore` 另外擋了 `*.jks`、`keystore.properties` 當保險）。

### 備份（重要）

**金鑰檔或密碼遺失 = 以後的新版都無法覆蓋安裝**，只能移除重裝，設定和紀錄跟著消失。
把 `doukeeper-release.jks` 和 `gradle.properties` 裡那四行一起存到密碼管理器或離線媒體。
不要放進任何 git repo，私有的也不要。

### 換電腦

1. 把金鑰檔複製到新電腦。
2. 在新電腦的 `~/.gradle/gradle.properties` 加上那四行，`DOUKEEPER_STORE_FILE` 改成新位置。

### 重新產生（只有金鑰真的遺失才需要）

```bash
keytool -genkeypair -v -keystore doukeeper-release.jks -storetype PKCS12 \
  -alias doukeeper -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=MrDaDaDo"
```

新金鑰簽出來的 APK 跟舊的視為不同發行者，手機上必須先移除舊版。

---

## 2. 每次發布

1. `app/build.gradle.kts` 的 `versionCode` +1，`versionName` 照 semver 調整。
2. 建置：

   ```bash
   JAVA_HOME="<JDK 21>" ./gradlew :app:testDebugUnitTest :app:assembleRelease
   ```

   產物自動複製到 `dist/DouKeeper-v{版本}.apk`。沒設定簽章參數時 `exportReleaseApk` 會直接失敗，
   不會產出沒簽章的檔案。
3. 確認簽章：

   ```bash
   apksigner verify --print-certs dist/DouKeeper-v{版本}.apk
   ```

   憑證 SHA-256 必須是上表那一串。
4. 打 tag、建 GitHub Release，附上 APK 與它的 SHA-256。

---

## 3. 從 debug 版換到 release 版（只會發生一次）

0.7.1 以前散佈的都是 debug 簽章。第一次裝 release 版時手機會說「與現有套件衝突」，
必須**先移除 debug 版**，App 內的設定與成功率統計會清掉。已經下載到 `Movies/DouKeeper` 等資料夾的檔案不受影響。
之後的 release 版就能一直直接覆蓋安裝。
