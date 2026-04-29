# Gift Details Message Builder Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 Gift triggered effect details message builder 第一版。

範圍包含：

- `GiftTriggeredEffectDetailsMessageBuilder`
- `GiftTriggeredEffectDetailsMessageBuilderTest`
- `PlayCardEffectResolutionService` 接線
- `CollabEffectResolutionService` 接線

不包含：

- PLAY_CARD outer confirm message
- COLLAB outer confirm message
- COLLAB trigger sections
- attack post-trigger message builder
- down event message
- pending decision writer
- pending context shape
- `match_pending_decisions` SQL 欄位

---

## 二、完成條件檢查

### builder 建立

狀態：完成

`GiftTriggeredEffectDetailsMessageBuilder` 已建立，負責 Gift details message。

輸出格式：

- `#N`
- optional card id
- `[TRIGGER_TYPE]`
- `效果類型：...`
- optional raw text

focused tests 覆蓋：

- null / empty input
- 單一 Gift trigger details
- 多個 Gift trigger details
- triggerType trim / uppercase
- requestedEffects trim / uppercase / 去重
- invalid requestedEffects fallback
- rawText append

### PLAY_CARD 接線

狀態：完成

`PlayCardEffectResolutionService` 已改用 `GiftTriggeredEffectDetailsMessageBuilder` 建立 Gift details。

保留：

- `確認 Gift 效果`
- `是否要執行本次 Gift 觸發效果？`
- pending decision 建立時機
- pending context `giftTriggers`
- selection context
- source card payload
- triggered resolution order

### COLLAB 接線

狀態：完成

`CollabEffectResolutionService` 已改用 `GiftTriggeredEffectDetailsMessageBuilder` 建立 Gift details。

保留：

- Collab outer confirm message
- Collab effect details
- Gift section outer label `[Gift]`
- trigger sections payload
- pending decision 建立時機
- source card payload
- triggered resolution order

---

## 三、Allow / Block 清單

### Allow

- PLAY_CARD / COLLAB 共用 `GiftTriggeredEffectDetailsMessageBuilder`。
- builder 可 normalize `triggerType` 與 `requestedEffects`，讓 Gift details message 與近期 Gift payload / summary builder 的 normalization 方向一致。
- attack post-trigger 可在下一輪評估是否接線。

### Block

- 不抽 PLAY_CARD outer confirm message。
- 不抽 COLLAB outer confirm message。
- 不抽 COLLAB trigger sections。
- 不接 attack post-trigger message。
- 不改 Down Event message。
- 不改 pending decision writer。
- 不改 pending context shape。
- 不改 `match_pending_decisions` SQL 欄位。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=GiftTriggeredEffectDetailsMessageBuilderTest,PlayCardEffectResolutionServiceTest,CollabEffectResolutionServiceTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

測試涵蓋：

- builder focused behavior
- PLAY_CARD Gift confirm pending baseline
- COLLAB Gift confirm pending baseline

---

## 五、剩餘缺口

無 blocker。

可後續補強但不阻擋本輪收斂：

- 評估 `AttackPostTriggerConfirmMessageBuilder` 是否接 `GiftTriggeredEffectDetailsMessageBuilder`。
- 若接 attack，需加跑 `AttackPostTriggerConfirmMessageBuilderTest`，且不可同時改 Down Event message。
- 補 API / integration smoke 驗證 pending decision message 文本。

---

## 六、結論

Gift triggered effect details message builder 第一版通過 acceptance review。

本輪已完成：

1. builder 建立
2. focused tests
3. PLAY_CARD 接線
4. COLLAB 接線
5. outer confirm / trigger sections 邊界保留
6. acceptance review

下一步建議評估 attack post-trigger Gift details message 是否接共用 builder；若接，必須保持 Down Event message 與 attack outer confirm message 不變。
