# 178-MatchEffect Card Selection Execution Service Extraction Acceptance Review

## 背景

前一步已將 pending decision 前的 selection probe 抽出，但 `MatchEffectService` 仍保留 SEARCH / RETURN 類效果的實際 execution：

- 從候選中挑選卡片。
- 移動 DECK / ARCHIVE / HAND / DECK_TOP 卡片。
- 處理牌庫頂搜尋後的剩餘牌 archive / deck bottom reorder。
- 組裝 search / return summary。

本步將這整段 actual execution flow 抽成 package-private service，讓主 service 保留效果分派與其他遊戲規則 orchestration。

## 本次完成內容

- 新增 `MatchCardSelectionExecutionService`。
- 新增 `MatchCardSelectionExecutionServiceTest`，先鎖定 dice condition 未命中時不觸發 JDBC 操作並回傳 no-op summary。
- 將 `MatchEffectService` 內下列方法搬到 execution service：
  - `executeSearchEffect(...)`
  - `executeReturnToHandEffect(...)`
  - `executeReturnToDeckTopEffect(...)`
  - `moveDeckCardToBottom(...)`
- `MatchCardSelectionSearchCandidateProvider` 補上 `selectSearchCards(...)`，讓 execution service 使用同一個候選查詢與挑選 gateway。
- `MatchEffectService` 的 support / gift / bloom / collab 分派改為委派 execution service。

## 行為邊界

本次不改變：

- support / gift / bloom / collab 的 effect dispatch 條件。
- SEARCH / RETURN SQL 語意與欄位。
- summary payload 欄位。
- selection probe 與 pending decision 建立入口。
- dice condition 判斷本身。

## 大檔尺寸變化

- `MatchEffectService.java`：`11,720` -> `11,374` 行，減少 `346` 行。
- `MatchCardSelectionExecutionService.java`：新增 `406` 行。
- `MatchCardSelectionExecutionServiceTest.java`：新增 `107` 行。
- `MatchCardSelectionProbeBuilder.java`：`200` -> `206` 行。
- `MatchCardSelectionSearchCandidateProvider.java`：`50` -> `59` 行。
- `MatchCardSelectionProbeBuilderTest.java`：`197` -> `207` 行。

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

下一批可繼續整理 selection execution service 內部共用卡片移動流程：

- 抽出 zone order / card move helper。
- 統一 RETURN_TO_HAND / RETURN_TO_DECK_TOP 的 selected card movement。
- 或轉向處理既有 Bloom structured return 行為差異，避免重構累積後才修規則。
