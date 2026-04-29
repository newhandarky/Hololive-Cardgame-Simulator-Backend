# Followup Interaction Pending Decision Writer Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 followup interaction pending decision writer cleanup。

範圍包含：

- 新增 package-private `FollowupInteractionPendingDecisionWriter`
- MatchAction 的 followup interaction pending insert 改為委派 writer
- 保留 MatchAction 內 effect summary -> `FollowupInteractionContext` 解析責任
- 新增 `FollowupInteractionPendingDecisionWriterTest`

不包含：

- LOOK_TOP_DECK / LOOK_OPPONENT_HAND / REORDER_DECK_BOTTOM 解析規則改動
- pending context shape 改動
- pending writer SQL schema 改動
- support / oshi skill action payload 改動

---

## 二、完成條件檢查

### writer extraction

狀態：完成

`FollowupInteractionPendingDecisionWriter` 負責：

- blocking pending 檢查
- 建立 followup pending context JSON
- insert `match_pending_decisions`
- 回傳 `FollowupInteractionDecision`

### MatchAction boundary

狀態：完成

MatchAction 的 `createFollowupInteractionPendingDecisionIfNeeded(...)` 現在只負責：

- 從 effect summary 解析 `FollowupInteractionContext`
- 無 context 時回傳 `null`
- 有 context 時委派 writer

---

## 三、Allow / Block 清單

### Allow

- 移出 pending insert SQL。
- 保留既有 context builder。
- 新增 writer focused test。

### Block

- 不改 interaction decision type。
- 不改 candidate cards / placement options context shape。
- 不改 support / oshi skill action payload。
- 不改 public API 或 schema。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=FollowupInteractionPendingDecisionWriterTest,FollowupPendingDecisionContextBuilderTest,FollowupInteractionContextBuilderTest test`
- `./mvnw -q -DskipTests compile`

commit 前需補：

- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估是否把 effect summary -> `FollowupInteractionContext` 的 extraction 也移出 MatchAction。
- 繼續縮小 support / oshi skill shared followup path。
- full integration suite 仍需另行穩定化規劃。

---

## 六、結論

Followup interaction pending decision writer cleanup 通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後評估 followup interaction context extraction service。
