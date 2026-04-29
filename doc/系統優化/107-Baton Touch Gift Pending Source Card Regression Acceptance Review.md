# Baton Touch Gift Pending Source Card Regression Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 baton touch Gift pending source card focused regression。

範圍包含：

- `BATON_TOUCH_BACK` Gift trigger pending context
- source card instance id / card id
- cards payload 內 source card
- Gift holder 與 source card 相同時的 pending context

不包含：

- baton touch production flow refactor
- source cards builder 改動
- Gift pending input shape 改動
- pending writer SQL
- schema / public API

---

## 二、完成條件檢查

### source card regression

狀態：完成

新增 `MatchActionServiceTest.createGiftTriggeredEffectConfirmPendingInteractionShouldKeepBatonTouchSourceCardContext`。

測試鎖住：

- pending source card instance id = baton touch target moved back card
- pending source card id = baton touch target card id
- context `cards` 仍包含 source card
- context `giftTriggers` 包含 `BATON_TOUCH_BACK`
- `giftHolderCardInstanceId` 可與 source card 相同

---

## 三、Allow / Block 清單

### Allow

- 補 focused regression。
- 使用既有 Gift pending private facade 建立 pending decision。

### Block

- 不改 production code。
- 不改 source cards builder。
- 不改 Gift pending input shape。
- 不改 pending writer SQL 或 schema。
- 不改 baton touch flow。

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

- 評估 baton touch Gift pending 是否要抽 dedicated adapter。
- 若抽 adapter，需保留本 regression，避免 source card 被無 source card Gift pending adapter 取代。
- full integration suite 仍需另行穩定化規劃。

---

## 六、結論

Baton touch Gift pending source card regression 通過 acceptance review。

下一步建議評估 baton touch Gift pending dedicated adapter；不應直接套用無 source card adapter。
