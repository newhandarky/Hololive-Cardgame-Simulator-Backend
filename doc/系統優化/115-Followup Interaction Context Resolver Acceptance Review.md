# Followup Interaction Context Resolver Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 followup interaction context resolver cleanup。

範圍包含：

- 新增 package-private `FollowupInteractionContextResolver`
- MatchAction 改為委派 resolver 取得 `FollowupInteractionContext`
- MatchAction 不再直接持有 `FollowupInteractionContextBuilder`
- MatchAction 不再直接持有 `FollowupCardCandidateLoader`
- 新增 `FollowupInteractionContextResolverTest`

不包含：

- `FollowupInteractionContextBuilder` 解析規則改動
- LOOK_TOP_DECK / LOOK_OPPONENT_HAND / REORDER_DECK_BOTTOM context shape 改動
- pending insert writer 改動
- support / oshi skill action payload 改動

---

## 二、完成條件檢查

### resolver extraction

狀態：完成

`FollowupInteractionContextResolver` 負責：

- 持有 `FollowupInteractionContextBuilder`
- 持有 `FollowupCardCandidateLoader`
- 將 `matchId` 注入 card candidate loader lambda
- 回傳 `FollowupInteractionContext`

### MatchAction boundary

狀態：完成

MatchAction 的 `extractFollowupInteractionDecisionContext(...)` 現在只保留相容用薄委派，不再組裝 card candidate loader lambda。

---

## 三、Allow / Block 清單

### Allow

- 移出 followup interaction context resolver wiring。
- 保留既有 context builder。
- 新增 resolver focused test。

### Block

- 不改 interaction decision type。
- 不改 candidate card fallback zone。
- 不改 context builder 判斷條件。
- 不改 pending writer SQL 或 schema。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=FollowupInteractionContextResolverTest,FollowupInteractionContextBuilderTest,FollowupInteractionPendingDecisionWriterTest test`
- `./mvnw -q -DskipTests compile`

commit 前需補：

- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估是否把 MatchAction 的 support / oshi skill shared followup payload 組裝再收斂。
- 繼續降低 MatchAction 對 pending decision SQL helper 的依賴。
- full integration suite 仍需另行穩定化規劃。

---

## 六、結論

Followup interaction context resolver cleanup 通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後評估 support / oshi skill shared followup payload cleanup。
