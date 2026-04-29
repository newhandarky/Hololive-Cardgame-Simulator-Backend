# MatchActionService Legacy Helper Cleanup Acceptance Review

更新日期：2026-04-29
結論：通過，無 blocker

---

## 一、Review 範圍

本次 review 對照：

- `78-MatchActionService Legacy Helper Cleanup Planning.md`
- `MatchPayloadJsonService`
- `MatchTimestampService`
- `GiftTriggerActionWriter`
- `MatchActionService`
- `MatchPayloadJsonServiceTest`
- `MatchTimestampServiceTest`
- `GiftTriggerActionWriterTest`
- `MatchActionServiceIntegrationTest#playToStageShouldTriggerGiftWhenQualifiedHolomemEntersStage`

目標是確認本輪 legacy helper cleanup 是否已把低風險 helper 實作責任移出 `MatchActionService`，同時不改 action payload shape、action order timing 或 transaction boundary。

---

## 二、完成條件檢查

| 項目 | 狀態 | 說明 |
| --- | --- | --- |
| JSON serializer helper | PASS | `MatchActionService.toJson(...)` 已委派 `MatchPayloadJsonService`，保留 legacy 失敗回傳 `{}` 語意。 |
| timestamp helper | PASS | `MatchActionService.touchUpdatedAt(...)` 已委派 `MatchTimestampService`。 |
| non-attack Gift action writer | PASS | `appendGiftTriggerActionsIfPresent(...)` 已委派 `GiftTriggerActionWriter`。 |
| 多筆 Gift action order | PASS | `GiftTriggerActionWriter` 單次 append 多筆 payload 時取一次 max order 並本地連續遞增。 |
| PLAY_CARD stage enter snapshot | PASS | Integration test 已覆蓋 `STAGE_ENTER` payload shape 與 `GIFT_TRIGGER` 早於 `TRIGGER_EFFECT_EXECUTED`。 |
| 規劃文件校準 | PASS | `78` 文件已標記 LHC-1 / LHC-2 完成，LHC-3 完成第一輪 baseline。 |

---

## 三、Allow / Block 清單

### 已允許暫留

- `MatchActionService` 仍保留 private facade：
  - `toJson(...)`
  - `touchUpdatedAt(...)`
  - `appendGiftTriggerActionsIfPresent(...)`
- `MatchActionService.appendAction(...)` 仍服務多個 legacy use case，暫不全域搬出。
- `AttackActionLogService.ACTION_TYPE_GIFT_TRIGGER` 與 `GiftTriggerActionWriter.ACTION_TYPE_GIFT_TRIGGER` 暫不合併。
- play support / damage received / down event 等其他 `GIFT_TRIGGER` 來源可在各自 use case cleanup 時補更細 snapshot。

### 已確認未做

- 未改 action payload key / shape。
- 未改 `GIFT_TRIGGER` action type。
- 未改 action order 計算來源。
- 未改 transaction boundary。
- 未把 attack action log writer 和 non-attack Gift writer 合併。
- 未搬動 `MatchEffectService` 內直接寫 `GIFT_TRIGGER` 的 legacy SQL。

---

## 四、測試覆蓋

Focused unit：

- `MatchPayloadJsonServiceTest`
- `MatchTimestampServiceTest`
- `GiftTriggerActionWriterTest`

Integration smoke：

- `MatchActionServiceIntegrationTest#playToStageShouldTriggerGiftWhenQualifiedHolomemEntersStage`

Compile：

- `./mvnw -q -DskipTests compile`

Static：

- `git diff --check`

---

## 五、測試缺口

目前沒有 blocker。

可後續補強：

1. 若要 cleanup play support / damage received / down event Gift paths，再補各自 payload snapshot。
2. 若要把 action type 常數共用化，先確認 attack / non-attack writer 不會被誤合併。
3. `MatchEffectService` legacy `GIFT_TRIGGER` SQL writer 應獨立規劃，不併入本輪 helper cleanup。

上述缺口不阻擋本階段，因為本輪目標是低風險 helper ownership cleanup，而不是完整 action log framework 化。

---

## 六、結論

`MatchActionService` legacy helper cleanup 第一輪通過 acceptance review。

下一步建議：

- 先做 code review / commit checkpoint。
- 後續若要繼續 cleanup，優先新增 `MatchEffectService` legacy `GIFT_TRIGGER` SQL writer 獨立規劃；不建議直接搬全域 `appendAction(...)`。
