# 177-MatchEffect Card Selection Probe Builder Extraction Acceptance Review

## 背景

`MatchEffectService` 在 search / return family 前兩步已完成 summary builder 與 request resolver 抽離，但仍保留「是否需要選牌互動」的探測流程：

- 依 effect type 判斷 probe 分支。
- 載入 SEARCH / RETURN_TO_HAND / RETURN_TO_DECK_TOP 候選。
- 依 dice condition 跳過 return 類互動。
- 將資料列映射成前端 pending decision candidate payload。

本步將這整段互動前候選探測流程抽成 package-private builder，讓主 service 保留高階流程與實際 execution。

## 本次完成內容

- 新增 `MatchCardSelectionProbeBuilder`。
- 新增 `MatchCardSelectionSearchCandidateProvider` 封裝 `MatchEffectSearchService` 對 probe builder 的查詢介面。
- 新增 `MatchCardSelectionProbeBuilderTest` 鎖定：
  - SEARCH 一般牌庫候選。
  - SEARCH look-top window 候選。
  - RETURN_TO_HAND archive 候選與 `LIMITED以外` 排除旗標。
  - RETURN_TO_HAND gift holder stack snapshot 來源。
  - RETURN_TO_DECK_TOP archive 候選。
  - dice condition 未命中時不建立 probe。
- `MatchEffectService` 改為委派選牌互動候選探測與 candidate mapping。

## 行為邊界

本次不改變：

- support / gift pending decision 建立入口。
- SEARCH / RETURN actual execution SQL。
- SearchCriteria 解析規則。
- dice condition 本身的判斷邏輯。
- 前端 decision candidate payload 欄位。

## 大檔尺寸變化

- `MatchEffectService.java`：`11,845` -> `11,720` 行，減少 `125` 行。
- `MatchCardSelectionProbeBuilder.java`：新增 `200` 行。
- `MatchCardSelectionSearchCandidateProvider.java`：新增 `50` 行。
- `MatchCardSelectionProbeBuilderTest.java`：新增 `197` 行。

## 驗證結果

已通過：

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

下一批可繼續處理 SEARCH / RETURN execution service extraction：

- 把 actual execution flow 與 SQL 更新從 `MatchEffectService` 移出。
- 保留 `applySupportEffect(...)` / gift execution orchestration 作為高階入口。
- 優先覆蓋 SEARCH、RETURN_TO_HAND、RETURN_TO_DECK_TOP 三個相鄰效果。
