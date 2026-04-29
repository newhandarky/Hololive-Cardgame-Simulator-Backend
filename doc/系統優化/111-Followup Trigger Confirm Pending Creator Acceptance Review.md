# Followup Trigger Confirm Pending Creator Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 MatchAction legacy trigger confirm helper cleanup。

範圍包含：

- 新增 package-private `FollowupTriggerConfirmPendingDecisionCreator`
- MatchAction 移除 private `createTriggeredEffectConfirmPendingInteraction(...)`
- MatchAction down-event followup 改為委派 creator
- 舊 MatchAction private helper reflection test 搬移到 creator test

不包含：

- down-event preview 擷取邏輯改動
- down-event pending context shape 改動
- `FollowupTriggerConfirmPendingDecisionWriter` SQL 改動
- Gift / Collab / Attack pending flow 改動
- public API 或 schema 改動

---

## 二、完成條件檢查

### creator extraction

狀態：完成

`FollowupTriggerConfirmPendingDecisionCreator` 負責：

- 接收 trigger confirm pending 欄位
- 建立 `FollowupTriggerConfirmPendingDecisionInput`
- 委派 `FollowupTriggerConfirmPendingDecisionWriter.create(...)`

### MatchAction cleanup

狀態：完成

MatchAction 已移除：

- `createTriggeredEffectConfirmPendingInteraction(...)`

剩餘 down-event followup 仍由 `createEffectPostTriggerConfirmPendingInteractionIfNeeded(...)` 判斷是否需要 pending，但底層 pending input / writer creation 已外移。

---

## 三、Allow / Block 清單

### Allow

- 新增 package-private creator。
- 移除 MatchAction generic trigger confirm private helper。
- 將舊 private helper 測試移到新 creator。

### Block

- 不改 `ACTION_TYPE_EFFECT_POST_TRIGGER`。
- 不改 `DOWN_EVENT` effect type。
- 不改 down-event additional context keys。
- 不改 pending min / max selection bounds rules。
- 不改 writer SQL 或 schema。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=FollowupTriggerConfirmPendingDecisionCreatorTest,FollowupTriggerConfirmPendingDecisionWriterTest test`
- `./mvnw -q -DskipTests compile`

commit 前需補：

- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 `createEffectPostTriggerConfirmPendingInteractionIfNeeded(...)` 是否能再拆成 dedicated down-event pending service。
- 繼續縮小 MatchAction support / oshi skill shared followup path。
- full integration suite 仍需另行穩定化規劃。

---

## 六、結論

Followup trigger confirm pending creator cleanup 通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後再評估 down-event pending service 抽出。
