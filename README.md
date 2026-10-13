# DouKeeper

在抖音（中國版）按「分享」時選這個 App，就能把作品存到手機。Android 原生（Kotlin）。

可以選要存什麼：**無浮水印影片**、有浮水印影片、音軌、背景音樂、圖文作品的圖片。

自用 / 側載，不上架 Google Play（理由見 `docs/SPEC.md` §1.3）。

---

## 現況

| 階段 | 狀態 |
|---|---|
| 規格與規劃 | ✅ `docs/SPEC.md` v0.1.0 |
| Phase 0 骨架與實測 | ⏳ 未開始 |
| 解析路徑實測 | ⏳ **尚未驗證**，見 `docs/SPEC.md` §5.B |
| 簽章金鑰 | ✅ 已產生，見 `docs/RELEASE.md` |

---

## 建置

**必須用 JDK 21。** Android Studio 內建的 JBR 是 JDK 25，Gradle 8.14 還不支援。

```bash
./gradlew :app:assembleDebug
```

正式版（固定金鑰簽章，散佈用這份）：`./gradlew :app:assembleRelease`，產物在 `dist/DouKeeper-v{版本}.apk`。
簽章參數的設定見 `docs/RELEASE.md`。

完整工具鏈版本見 `docs/SPEC.md` §12。

---

## 文件

| 檔案 | 內容 |
|---|---|
| `docs/SPEC.md` | **主文件**。決策紀錄、使用流程、架構、解析與下載規格、Phase 任務與驗收標準、風險清單 |
| `docs/RELEASE.md` | 簽章金鑰的保管、備份、換電腦，以及每次發布的步驟 |

---

## 重要前提

- **抖音的解析方式隨時可能失效。** 抖音改版時先把新頁面存成測試夾具、讓測試變紅，再修。
- **側載也要固定簽章金鑰。** 換台電腦用 debug 簽章建置的 APK 不能覆蓋安裝，
  只能先移除，下載紀錄會跟著消失。
