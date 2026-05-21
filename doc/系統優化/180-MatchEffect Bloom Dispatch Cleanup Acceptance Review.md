# MatchEffect Bloom Dispatch Cleanup Acceptance Review

日期：2026-05-21
狀態：已完成
commit 建議：`後端：整理 Bloom 效果分派流程`

## 背景

`MatchEffectService.applyBloomTriggeredEffects(...)` 原本同時負責：

- Bloom effect plan 解析後的 runtime 欄位補齊。
- HBP04-059 骰子流程補值。
- 逐一分派所有 Bloom effect type。
- 組裝回傳 summary。

這讓 Bloom 入口方法在閱讀時必須跨過一大段 switch 才能看出高階流程。本批先把分派區塊集中到同一 class 的私有 dispatcher，暫不搬到新 component，避免同時改動依賴注入與行為邊界。

## 本批完成內容

- 新增 `executeBloomEffectTypes(...)`，集中處理 Bloom effect type switch。
- 新增 `BloomDispatchResult`，統一回傳：
  - `executed`
  - `unsupported`
  - `skippedEffects`
- `applyBloomTriggeredEffects(...)` 保留高階流程：
  - 解析 Bloom plan。
  - 補 `matchId`、`sourceUserId`、`sourceHolomemCardInstanceId`。
  - 處理 HBP04-059 dice runtime 欄位。
  - 呼叫 Bloom dispatcher。
  - 組裝 summary。
- 保留所有 effect execution method 與 SQL 行為，不在本批搬動 `BLOOM_FROM_ARCHIVE`、SEARCH、RETURN、DAMAGE 等實際執行邏輯。

## 影響範圍

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`
- `doc/系統優化/00-系統優化總覽.md`
- `doc/系統優化/05-重構進度追蹤.md`

## 大檔尺寸變化

- `MatchEffectService.java`：`11,374` -> `11,407` 行，增加 `33` 行。

本批沒有讓主檔行數下降，原因是先在同一 class 內建立 dispatcher 邊界與結果模型。收益是 `applyBloomTriggeredEffects(...)` 的高階流程變短、責任更清楚；下一批若抽成 `MatchBloomEffectDispatcher` 類 component，才會實際降低 `MatchEffectService` 行數。

## 驗證結果

已通過：

- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchCardSelectionExecutionServiceTest,MatchCardSelectionProbeBuilderTest,MatchCardSelectionRequestResolverTest,MatchCardSelectionSummaryBuilderTest test`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#bloomShouldTriggerReturnToHandEffectFromPassiveText+bloomShouldTriggerReturnToHandEffectFromStructuredDefinition+bloomShouldTriggerReturnToDeckTopEffectFromStructuredDefinition test`

補充：

- sandbox 內跑 integration tests 會因 Docker / PostgreSQL 權限被擋，已提高權限後驗證。
- `MatchActionServiceIntegrationTest#bloomShouldTriggerBloomFromArchiveEffectFromStructuredDefinition` 在本批修改後仍失敗；已於乾淨 HEAD worktree 重跑確認同樣失敗，屬既有行為差異，不是本批 dispatcher 抽離造成。

## 殘留風險

- Bloom dispatcher 仍留在 `MatchEffectService`，主檔行數短期增加。
- Collab 仍有相似 effect type switch，尚未整理。
- `BLOOM_FROM_ARCHIVE` structured integration test 目前仍有既有行為差異：預期 Archive bloom card 移到目標 Holomem，但實際目標 Holomem 未更新。

## 下一步建議

1. 先修正 `bloomShouldTriggerBloomFromArchiveEffectFromStructuredDefinition`，把 Archive Bloom 行為差異收斂。
2. 行為修正通過後，再抽 `MatchBloomEffectDispatcher`，將本批新增的 dispatcher 從 `MatchEffectService` 搬到 package-private component。
3. Bloom dispatcher 穩定後，再用同樣方式整理 Collab effect type switch。
