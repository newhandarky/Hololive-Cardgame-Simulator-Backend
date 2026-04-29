# MatchActionService Legacy Helper Cleanup Planning

更新日期：2026-04-29
結論：serializer / timestamp helper 已落地，下一步收斂 Gift trigger action writer baseline

---

## 一、背景

ATTACK pilot cleanup 已完成，`AttackArtApplicationAdapterFactory` 不再依賴 `MatchActionService` private helper bridge，attack action log writer 也已搬成 `AttackActionWriterAdapter`。

下一輪不建議直接把所有 action writer 搬出，因為 `MatchActionService.appendAction(...)` 同時服務多個 use case；若沒有跨 use case baseline，容易改動 action order 或 payload timing。

本文件先盤點仍留在 `MatchActionService` 的共用 helper，決定下一步安全切法。

---

## 二、目前 helper 使用量與落地狀態

盤點命令：

- `rg -c "toJson\\(" src/main/java/com/hololive/cardgame/service/MatchActionService.java`
- `rg -c "touchUpdatedAt\\(" src/main/java/com/hololive/cardgame/service/MatchActionService.java`
- `rg -c "appendGiftTriggerActionsIfPresent\\(" src/main/java/com/hololive/cardgame/service/MatchActionService.java`

原始盤點結果：

| helper | 原出現次數 | 目前狀態 | 判讀 |
| --- | ---: | --- |
| `toJson(...)` | 36 | 已委派 `MatchPayloadJsonService` | `MatchActionService.toJson(...)` 已只保留 private facade，實作責任已移出。 |
| `touchUpdatedAt(...)` | 33 | 已委派 `MatchTimestampService` | `MatchActionService.touchUpdatedAt(...)` 已只保留 private facade，實作責任已移出。 |
| `appendGiftTriggerActionsIfPresent(...)` | 2 | 已委派 `GiftTriggerActionWriter` | writer 已存在，且已補多筆 payload action order baseline。 |

目前 focused baseline：

- `MatchPayloadJsonServiceTest`
- `MatchTimestampServiceTest`
- `GiftTriggerActionWriterTest`

目前 legacy API smoke：

- `MatchActionServiceIntegrationTest#playToStageShouldTriggerGiftWhenQualifiedHolomemEntersStage`

---

## 三、建議切法

### Step LHC-1：共用 JSON serializer（已完成）

完成狀態：

- `MatchPayloadJsonService` 已存在。
- `MatchActionService.toJson(...)` 已委派到共用 service。
- `MatchPayloadJsonServiceTest` 已覆蓋正常序列化與失敗回傳 `{}` 的 legacy 語意。

不做：

- 不改 payload key / shape
- 不改 exception message 的對外語意
- 不一次替換所有 service 內的 private serializer

驗證：

- `MatchPayloadJsonServiceTest`
- compile
- 代表性 action payload integration smoke 可依後續改動再補

### Step LHC-2：共用 timestamp helper（已完成）

完成狀態：

- `MatchTimestampService` 已存在。
- `MatchActionService.touchUpdatedAt(...)` 已委派到共用 service。
- `MatchTimestampServiceTest` 已覆蓋 timestamp refresh。

不做：

- 不改 match save timing
- 不改 phase transition timing
- 不改 transaction boundary

驗證：

- `MatchTimestampServiceTest`
- 代表性 use case integration smoke 可依後續替換 call site 再補

### Step LHC-3：Gift trigger action helper 評估（已完成第一輪 baseline）

已完成：

- `appendGiftTriggerActionsIfPresent(...)` 已委派 `GiftTriggerActionWriter`。
- `GiftTriggerActionWriterTest` 已覆蓋：
  - empty payload 不寫入
  - 單筆 payload action type / payload / action order
  - 多筆 payload 在同一次 writer 呼叫中連續遞增 action order
- `playToStageShouldTriggerGiftWhenQualifiedHolomemEntersStage` legacy API smoke 已通過，並已補 stage enter payload / action order snapshot：
  - `triggerType = STAGE_ENTER`
  - `giftHolderCardId`
  - `sourceCardInstanceId`
  - `requestedEffects = [DRAW]`
  - `GIFT_TRIGGER` action order 早於同次 confirm 的 `TRIGGER_EFFECT_EXECUTED`
- COLLAB Gift path 已有 payload shape 與 action order relation baseline。

仍待評估：

- play support / damage received / down event 等其他 `GIFT_TRIGGER` 來源是否需要同等 snapshot，可在各自 use case cleanup 時補，不阻擋本輪。
- `AttackActionLogService.ACTION_TYPE_GIFT_TRIGGER` 與 `GiftTriggerActionWriter.ACTION_TYPE_GIFT_TRIGGER` 是否需要後續合併成共用常數。
- `MatchEffectService` 內直接寫 `GIFT_TRIGGER` 的 legacy SQL 是否應獨立規劃，不在本輪直接搬動。

不做：

- 不直接把 `appendAction(...)` 全域搬出
- 不改 action order 計算來源與 transaction boundary
- 不把 attack action log writer 和 non-attack gift writer 一次合併

驗證：

- `GIFT_TRIGGER` action payload snapshot
- action order regression

---

## 四、下一步建議

下一步建議進 LHC-3 acceptance / planning closure，不再重做 LHC-1 / LHC-2：

1. 記錄本輪 helper cleanup acceptance：serializer / timestamp / non-attack Gift writer baseline 已完成。
2. 暫不抽共用 action type 常數；若只為去字串重複，不急著做。
3. `MatchEffectService` 的 legacy SQL writer 另開規劃，不併入本輪 helper cleanup。

這個切法能維持 action writer 邊界穩定，同時避免把 legacy SQL writer 與 action writer cleanup 混在同一輪。
