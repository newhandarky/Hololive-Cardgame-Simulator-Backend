# Effect Followup Decision Resolver Acceptance Review

日期：2026-04-29
狀態：已完成
範圍：support / oshi skill 效果後續 pending decision resolver 收斂

---

## 一、目標

本步目標是收斂 MatchAction 內 support / oshi skill 共用的效果後續 pending decision 建立流程。

原本 MatchAction 入口重複執行：

1. 嘗試建立 effect post-trigger confirm pending
2. 若沒有 deferred down event，再建立 LOOK_TOP_DECK / LOOK_OPPONENT_HAND / REORDER_DECK_BOTTOM 等一般 followup interaction pending
3. 將 decision id/type 寫回 action payload

本步只抽出 decision resolution 流程，不改 payload shape、pending schema、互動類型或效果解析規則。

---

## 二、完成內容

- 新增 `EffectFollowupDecisionResolver`
- 將 post-trigger confirm pending 優先於一般 followup interaction pending 的順序集中到 resolver
- MatchAction 的 `PLAY_SUPPORT` 直接結算入口改用 resolver
- MatchAction 的 support selection pending resolve 入口改用 resolver
- MatchAction 的 `USE_OSHI_SKILL` 入口改用 resolver
- 保留 pending trigger confirm resolve 只建立一般 followup interaction decision 的語意
- 移除 MatchAction 內部的 followup pending wrapper helper
- 新增 `EffectFollowupDecisionResolverTest`

---

## 三、Allow / Block 清單

### Allow

- 抽出 support / oshi skill 共用的 followup decision resolution。
- 保留 post-trigger confirm pending 優先順序。
- 保留一般 followup interaction pending fallback。
- 保留 MatchAction action payload 寫回欄位。

### Block

- 不改 `match_pending_decisions` schema。
- 不改 decision type。
- 不改 `pendingInteractionDecisionId` / `pendingInteractionDecisionType` payload key。
- 不改 LOOK_TOP_DECK / LOOK_OPPONENT_HAND / REORDER_DECK_BOTTOM context shape。
- 不改 effect summary 格式。
- 不改 support / oshi skill 的產品規則。

---

## 四、驗證重點

`EffectFollowupDecisionResolverTest` 覆蓋：

- 有 post-trigger confirm decision 時，直接回傳 confirm decision，不再解析一般 followup interaction context。
- 無 post-trigger confirm decision 時，fallback 建立一般 followup interaction decision。

本步亦需通過：

- `FollowupInteractionContextResolverTest`
- `FollowupInteractionPendingDecisionWriterTest`
- `EffectPostTriggerPendingServiceTest`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 盤點 MatchAction 內 support / oshi skill payload 建立是否可再抽出，但需避免把 action log shape 改掉。
- 繼續清理 pending decision payload append helper 或 phase/touch/save 重複流程。
- full `MatchActionServiceIntegrationTest` 仍有既有廣泛失敗，需獨立穩定化。

---

## 六、結論

Effect followup decision resolver cleanup 通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後評估 support / oshi skill payload assembly 或 pending payload append helper 的下一個最小拆分。
