# Baton Touch Gift Pending Adapter Cleanup Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 baton touch Gift pending dedicated local adapter cleanup。

範圍包含：

- 新增 `createBatonTouchGiftTriggerDecision(...)`
- baton touch flow 改為委派 dedicated adapter
- 保留 baton touch source card instance id / card id
- 保留 Gift source cards payload shape

不包含：

- source cards builder 改動
- Gift pending input shape 改動
- pending writer SQL
- schema / public API
- baton touch flow rule changes

---

## 二、完成條件檢查

### adapter extraction

狀態：完成

baton touch Gift pending inline code 已改為：

- `createBatonTouchGiftTriggerDecision(...)`

adapter 內部仍呼叫：

- `buildGiftTriggerInteractionCards(...)`
- `createGiftTriggeredEffectConfirmPendingInteraction(...)`

### source card boundary

狀態：完成

adapter 明確接收：

- `sourceCardInstanceId`
- `sourceCardId`

並把兩者傳入 source cards builder 與 Gift pending input。

---

## 三、Allow / Block 清單

### Allow

- 新增 MatchAction local private adapter。
- 移除 baton touch flow 內 inline source cards + Gift pending creation。
- 保留剛補的 source card focused regression。

### Block

- 不改 baton touch source card semantics。
- 不套用無 source card Gift pending adapter。
- 不改 Gift pending input shape。
- 不改 source cards builder。
- 不改 writer SQL 或 schema。
- 不改 baton touch phase / cost / movement rules。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=MatchActionServiceTest test`
- `./mvnw -q -DskipTests compile`

commit 前需補：

- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估是否抽出 package-private Gift pending decision creator，統一無 source card與 baton touch source card兩種入口。
- 保留 source card regression，避免 baton touch 被誤接到無 source card adapter。
- full integration suite 仍需另行穩定化規劃。

---

## 六、結論

Baton touch Gift pending adapter cleanup 通過 acceptance review。

下一步建議盤點所有 remaining `createGiftTriggeredEffectConfirmPendingInteraction(...)` call site，判斷是否已足夠清晰，或是否需要抽一個小型 Gift pending decision creator。
