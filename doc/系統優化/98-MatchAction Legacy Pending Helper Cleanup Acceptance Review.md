# MatchAction Legacy Pending Helper Cleanup Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 `MatchActionService` legacy triggered-effect confirm pending helper cleanup。

範圍包含：

- `MatchActionService.createTriggeredEffectConfirmPendingInteraction(...)`
- `FollowupTriggerConfirmPendingDecisionWriter` delegation 接線
- Gift legacy pending context regression
- generic additional context bounds regression
- attack post-trigger pending context regression
- no-context helper overload 移除

不包含：

- public action API
- source cards builder 搬移
- attack pending conversion 搬移
- pending context builders 搬移
- `FollowupTriggerConfirmPendingDecisionWriter` SQL 改動
- `match_pending_decisions` schema
- use case timing

---

## 二、完成條件檢查

### writer delegation

狀態：完成

`MatchActionService` 已持有 `FollowupTriggerConfirmPendingDecisionWriter`，並透過 constructor 以既有 `jdbcTemplate` / `objectMapper` 建立。

`createTriggeredEffectConfirmPendingInteraction(...)` with-context helper 已委派 writer 建立 `FOLLOWUP_TRIGGER_CONFIRM` pending decision。

### overload cleanup

狀態：完成

原本未帶 additional context 的 private overload 已移除。

目前保留的 helper surface 只剩 with-context 版本，所有實際呼叫點皆明確傳入 additional context：

- attack post-trigger pending context
- Gift triggered-effect pending context
- down-event post-trigger pending context

### focused regression

狀態：完成

`MatchActionServiceTest` 已覆蓋：

- Gift legacy pending context shape
- generic additional context `minSelect` / `maxSelect` / cards fallback
- attack post-trigger pending `sourceActionType`
- attack post-trigger pending additional context section type

---

## 三、Allow / Block 清單

### Allow

- `MatchActionService` generic pending helper 內部委派 writer。
- 移除已無呼叫點的 no-context private overload。
- 以 focused unit tests 鎖住 pending context shape。

### Block

- 不改 public action APIs。
- 不搬 Gift source cards builder。
- 不搬 attack pending conversion。
- 不改 pending context builders。
- 不改 writer SQL。
- 不改 pending schema。
- 不改 use case timing。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=MatchActionServiceTest,FollowupTriggerConfirmPendingDecisionWriterTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

測試判讀：

- focused suite 已覆蓋這輪 helper cleanup 的直接風險。
- full `MatchActionServiceIntegrationTest` 仍有既有廣泛失敗，未作為本輪 blocker。

---

## 五、剩餘缺口

無 blocker。

可後續補強但不阻擋本輪收斂：

- 評估 `createGiftTriggeredEffectConfirmPendingInteraction(...)` 是否能縮小為更薄的 adapter wrapper。
- 拆出或測試 Gift source cards builder 前，需先保護 defender Gift、archive Gift、holopower Gift source cards shape。
- 另行規劃 full integration suite 穩定化。

---

## 六、結論

MatchAction legacy pending helper cleanup 通過 acceptance review。

本輪已完成：

1. writer delegation 接線驗收
2. attack post-trigger context regression 補強
3. no-context helper overload 移除
4. focused tests 與 compile 驗證
5. helper cleanup acceptance review

下一步建議進入 Gift source cards builder focused regression / extraction planning，先鎖住 source cards shape，再評估是否縮小 `createGiftTriggeredEffectConfirmPendingInteraction(...)` wrapper。
