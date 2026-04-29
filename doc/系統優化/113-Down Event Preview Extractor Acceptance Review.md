# Down Event Preview Extractor Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 down-event preview extractor 共用化。

範圍包含：

- 新增 package-private `DownEventPreviewExtractor`
- `AttackDownService` 改用共用 extractor
- `EffectPostTriggerPendingService` 改用共用 extractor
- 移除兩個 service 內重複的 nested down-event preview extraction
- 新增 `DownEventPreviewExtractorTest`

不包含：

- attack down 判定規則改動
- effect post-trigger pending context shape 改動
- pending writer SQL 或 schema 改動
- down-event resolution / apply flow 改動

---

## 二、完成條件檢查

### extractor sharing

狀態：完成

`DownEventPreviewExtractor` 統一處理：

- top-level `downEvent`
- nested `executedEffects[].downEvent`
- `triggered = true`
- `deferred = true`

### caller boundary

狀態：完成

`AttackDownService` 仍只把 preview 放入 `AttackDownResult`。

`EffectPostTriggerPendingService` 仍只在 extractor 找到 deferred down-event 時建立 pending。

---

## 三、Allow / Block 清單

### Allow

- 移除重複 extractor 程式。
- 新增 focused extractor test。
- 保留既有 attack down / pending service focused tests。

### Block

- 不改 `AttackDownResult` shape。
- 不改 `EFFECT_POST_TRIGGER` pending source action。
- 不改 `DOWN_EVENT` pending effect type。
- 不改 down-event additional context keys。
- 不改 writer SQL 或 schema。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=DownEventPreviewExtractorTest,AttackDownServiceTest,EffectPostTriggerPendingServiceTest test`
- `./mvnw -q -DskipTests compile`

commit 前需補：

- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 MatchAction support / oshi skill shared followup path 是否可拆 service。
- 繼續縮小 MatchAction 內 followup interaction pending decision 建構。
- full integration suite 仍需另行穩定化規劃。

---

## 六、結論

Down-event preview extractor 共用化通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後評估 support / oshi skill followup path cleanup。
