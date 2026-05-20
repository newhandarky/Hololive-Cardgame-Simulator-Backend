# 179-MatchCardSelection Execution Move Helper Cleanup Acceptance Review

日期：2026-05-20
狀態：已完成

## 背景

`MatchCardSelectionExecutionService` 已從 `MatchEffectService` 抽出 SEARCH / RETURN actual execution flow，但內部仍有多段相似的卡片移動 SQL 迴圈：

- SEARCH / RETURN_TO_HAND 將已選牌移到手牌。
- RETURN_TO_DECK_TOP 將已選牌移到牌庫頂。
- SEARCH look-top window 將未選剩餘牌移到 Archive。

本批不調整規則語意，也不處理 Bloom structured return 行為差異，只整理 selection execution service 內部的相鄰責任。

## 本批變更

- 新增 `MovedCards` 共用結果物件，集中維護：
  - 成功移動的 `cardInstanceIds`
  - 成功移動的 `cardIds`
  - 只有 SQL update 成功且資料列有效時才寫入 summary
- 抽出 `moveSelectedCardsToHand(...)`。
- 抽出 `moveSelectedCardsToDeckTop(...)`。
- 抽出 `moveUnselectedTopWindowCardsToArchive(...)`。
- 抽出 `collectCardInstanceIds(...)`，統一已選牌 id 收集。
- 補強 `MatchCardSelectionExecutionServiceTest`：
  - `MovedCards` 只記錄成功移動且資料有效的牌。
  - `executeReturnToDeckTopEffect(...)` 只將 update 成功的牌寫進 returned summary。

## 責任邊界

- `MatchCardSelectionExecutionService` 仍負責 actual execution orchestration：
  - 判斷 SEARCH / RETURN 參數
  - 呼叫 candidate provider / probe builder
  - 呼叫 summary builder
- 新 helper 只負責單一資料移動流程與移動結果收集。
- SQL 條件維持原本來源區檢查：
  - 搜尋來源區移到 `HAND`
  - `ARCHIVE` 移到 `HAND`
  - `ARCHIVE` 移到 `DECK`
  - `DECK` 未選 top window 移到 `ARCHIVE`

## 行數變化

- `MatchEffectService.java`：`11,374` -> `11,374` 行，無變化。
- `MatchCardSelectionExecutionService.java`：`406` -> `424` 行，增加 `18` 行。

本批主要降低 `MatchCardSelectionExecutionService` 內部重複移動流程，不以降低主 service 行數為目標。

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchCardSelectionExecutionServiceTest test`
- `./mvnw -q -Dtest=MatchCardSelectionProbeBuilderTest test`
- `./mvnw -q -Dtest=MatchCardSelectionRequestResolverTest test`
- `./mvnw -q -Dtest=MatchCardSelectionSummaryBuilderTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#playSupportSearchShouldMoveMatchingCardFromDeckToHand,MatchActionServiceIntegrationTest#playSupportSearchShouldCreateDeckBottomReorderInteractionAndResolveInSpecifiedOrder,MatchActionServiceIntegrationTest#playSupportLookTopDeckShouldExposePendingInteractionWithCardContext test`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#bloomShouldTriggerReturnToHandEffectFromPassiveText test`
- `git diff --check`

補充：

- sandbox 內跑整合測試會因 Docker / PostgreSQL 權限被擋，已提高權限後驗證通過。

## 下一步建議

下一批建議不要再繼續只整理 helper，而是修正已知行為差異：

- `bloomShouldTriggerReturnToHandEffectFromStructuredDefinition`
- `bloomShouldTriggerReturnToDeckTopEffectFromStructuredDefinition`

目前 structured definition 與 passive text 在 Bloom return 類效果上的行為不同，應以 focused integration tests 先鎖住預期，再修正 structured effect execution flow。
