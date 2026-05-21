# MatchBloomEffectDispatcher Extraction Acceptance Review

日期：2026-05-21
狀態：已完成
commit 建議：`後端：抽出 Bloom 效果分派器`

## 背景

前一批 `180-MatchEffect Bloom Dispatch Cleanup Acceptance Review.md` 先在 `MatchEffectService` 內建立 `executeBloomEffectTypes(...)` 與 `BloomDispatchResult`，讓 `applyBloomTriggeredEffects(...)` 的高階流程和 effect type switch 分開。

本批接著把該 switch 搬出主 service，形成 package-private `MatchBloomEffectDispatcher`。目標是降低 `MatchEffectService` 的單檔負擔，並讓 Bloom triggered effect 的分派責任有獨立邊界。

## 本批完成內容

- 新增 `MatchBloomEffectDispatcher`：
  - 接收 `MatchCardSelectionExecutionService`。
  - 接收 `MatchEffectService` 作為暫時性 execution bridge。
  - 集中處理 Bloom effect type switch。
  - 回傳 `BloomDispatchResult`，包含 `executed`、`unsupported`、`skippedEffects`。
- `MatchEffectService.applyBloomTriggeredEffects(...)` 改為呼叫 `bloomEffectDispatcher.execute(...)`。
- 移除 `MatchEffectService` 內的 `executeBloomEffectTypes(...)` 與內嵌 `BloomDispatchResult`。
- 將 Bloom dispatcher 需要呼叫的 execution helper 調整為 package-private，作為同 package 內的過渡委派邊界。
- 本批未搬動實際 SQL execution method，避免把分派抽離與資料寫入行為重組混在同一批。

## 影響範圍

- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`
- `src/main/java/com/hololive/cardgame/service/MatchBloomEffectDispatcher.java`
- `doc/系統優化/00-系統優化總覽.md`
- `doc/系統優化/05-重構進度追蹤.md`

## 大檔尺寸變化

- `MatchEffectService.java`：`11,407` -> `11,166` 行，減少 `241` 行。
- 新增 `MatchBloomEffectDispatcher.java`：`266` 行。

本批讓 `MatchEffectService` 實際下降 241 行。總行數不會等量下降，因為分派流程被搬到新 component；主要收益是主 service 少掉 Bloom effect type switch 這段相鄰責任。

## 驗證結果

已通過：

- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchCardSelectionExecutionServiceTest,MatchCardSelectionProbeBuilderTest,MatchCardSelectionRequestResolverTest,MatchCardSelectionSummaryBuilderTest test`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#bloomShouldTriggerReturnToHandEffectFromPassiveText+bloomShouldTriggerReturnToHandEffectFromStructuredDefinition+bloomShouldTriggerReturnToDeckTopEffectFromStructuredDefinition+bloomShouldTriggerBloomFromArchiveEffectFromStructuredDefinition test`

補充：

- integration tests 需要 Testcontainers / PostgreSQL，已使用提高權限執行。

## 殘留風險

- `MatchBloomEffectDispatcher` 仍以 `MatchEffectService` 作為 execution bridge，代表實際 effect execution 邏輯尚未完全離開主 service。
- 多個 helper 從 `private` 放寬為 package-private，短期可讓 dispatcher 抽離成立，但後續應以更小的 execution service 或 gateway 收斂可見範圍。
- Collab effect type switch 尚未抽出，`MatchEffectService` 仍保留相似 orchestration 責任。

## 下一步建議

1. 先不要一次搬所有 Bloom SQL；建議從 Bloom 內部再挑一個穩定群組，例如 `DRAW` / `LOOK_*` / `NO_OP` 這類低風險 execution helper，抽成真正的 execution service。
2. 若希望繼續以較大批次降行數，也可抽 `MatchCollabEffectDispatcher`，先複製本批模式處理 Collab switch。
3. 中期目標是讓 dispatcher 不再依賴整個 `MatchEffectService`，改依賴更窄的 execution component。
