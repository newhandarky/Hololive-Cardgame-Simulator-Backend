# Archive Bloom Structured Confirm Flow Acceptance Review

日期：2026-05-21
狀態：已完成
commit 建議：`測試：對齊 Archive Bloom 確認流程`

## 背景

`bloomShouldTriggerBloomFromArchiveEffectFromStructuredDefinition` 原本在呼叫 `matchActionService.bloom(...)` 後，直接斷言 Archive Bloom 已經套用到目標 Holomem。

但目前 Bloom triggered effect 的設計是先建立 `TRIGGER_EFFECT_CONFIRM` pending decision，再由確認流程執行實際效果。相鄰測試 `RETURN_TO_HAND` 與 `RETURN_TO_DECK_TOP` 已採用相同節奏：

1. Bloom 後先檢查 pending decision。
2. confirm 前來源卡仍停在原區域。
3. resolve pending 後才檢查實際移動。

因此 Archive Bloom 測試失敗不是 production code 的 Archive Bloom SQL 壞掉，而是測試在 confirm 前檢查了 confirm 後才會發生的狀態。

## 本批完成內容

- 更新 `bloomShouldTriggerBloomFromArchiveEffectFromStructuredDefinition`：
  - 先驗證 `TRIGGER_EFFECT_CONFIRM` pending decision 已建立。
  - 驗證 pending context 包含 `sourceActionType = BLOOM` 與 `BLOOM_FROM_ARCHIVE`。
  - confirm 前驗證目標 Holomem 仍是 Debut、Archive bloom card 仍在 `ARCHIVE`。
  - 呼叫 `resolvePendingInteractionIfExists(...)` 後，再驗證 Archive bloom card 移到 `STAGE` 並更新目標 Holomem。
- 未修改 production code。

## 影響範圍

- `src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java`
- `doc/系統優化/00-系統優化總覽.md`
- `doc/系統優化/05-重構進度追蹤.md`

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#bloomShouldTriggerBloomFromArchiveEffectFromStructuredDefinition test`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#bloomShouldTriggerReturnToHandEffectFromPassiveText+bloomShouldTriggerReturnToHandEffectFromStructuredDefinition+bloomShouldTriggerReturnToDeckTopEffectFromStructuredDefinition+bloomShouldTriggerBloomFromArchiveEffectFromStructuredDefinition test`
- `./mvnw -q -DskipTests compile`

補充：

- integration tests 需要 Testcontainers / PostgreSQL，已使用提高權限執行。

## 下一步建議

- 回到結構拆分主線：把 `executeBloomEffectTypes(...)` 搬成 `MatchBloomEffectDispatcher` package-private component。
- 該批會讓 `MatchEffectService` 實際降行數，並為後續 Collab dispatcher 抽離建立可複用模式。
