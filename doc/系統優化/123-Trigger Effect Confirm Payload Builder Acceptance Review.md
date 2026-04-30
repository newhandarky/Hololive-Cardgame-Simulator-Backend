# Trigger Effect Confirm Payload Builder Acceptance Review

日期：2026-04-30
狀態：已完成
範圍：MatchAction trigger effect confirm base payload 組裝抽出

---

## 一、目標

本步目標是收斂 MatchAction 內 trigger effect confirm 決策解析時的 base payload 組裝。

本步只搬移 base payload 欄位，不改：

- confirmed / skipped 判斷
- confirmed 後效果結算
- payload 後續補充 effect result
- action type
- pending decision schema

---

## 二、完成內容

- 新增 `TriggerEffectConfirmPayloadBuilder`
- 抽出 trigger effect confirm base payload：
  - `decisionId`
  - `interactionType`
  - `sourceActionType`
  - `confirmed`
- MatchAction 改為先由 builder 建立 base payload，再交給 `applyConfirmedTriggerEffectResolution(...)` 補 confirmed effect result
- 新增 `TriggerEffectConfirmPayloadBuilderTest`

---

## 三、Allow / Block 清單

### Allow

- 抽出 trigger effect confirm base payload 組裝。
- 保留 `interactionType = TRIGGER_EFFECT_CONFIRM`。
- 保留 confirmed boolean 欄位。

### Block

- 不改 `applyConfirmedTriggerEffectResolution(...)`。
- 不改 `TRIGGER_EFFECT_EXECUTED` / `TRIGGER_EFFECT_SKIPPED` action type。
- 不改 pending decision resolve 順序。
- 不改 effect result payload shape。

---

## 四、驗證重點

`TriggerEffectConfirmPayloadBuilderTest` 覆蓋：

- confirmed = true 的 base payload。
- confirmed = false 的 base payload。

本步亦需通過：

- `TriggerEffectConfirmPayloadBuilderTest`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 send cheer interaction confirmed payload builder。
- 盤點 trigger effect confirmed 後的 effect result payload 是否可再拆，但需先確認各效果類型欄位。
- full `MatchActionServiceIntegrationTest` 仍有既有廣泛失敗，需獨立穩定化。

---

## 六、結論

Trigger effect confirm payload builder cleanup 通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後評估 send cheer interaction payload builder 的下一個最小拆分。
