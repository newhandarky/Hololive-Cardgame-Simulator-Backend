# 176-MatchEffect Card Selection Request Resolver Extraction Acceptance Review

## 背景

前一步已將 search / return 類效果的 summary payload 組裝抽到 `MatchCardSelectionSummaryBuilder`，但 `MatchEffectService` 仍保留多個與「選牌請求」相關的推斷方法：

- search 可選張數
- search 來源區
- 看牌庫頂張數
- return / discard / attach 等通用 action 張數
- Bloom gift holder stack snapshot 的 return-to-hand 來源判斷

這些方法本身不依賴資料庫，適合先抽成 package-private resolver，降低主 service 的混合責任。

## 本次完成內容

- 新增 `MatchCardSelectionRequestResolver`。
- 新增 `MatchCardSelectionRequestResolverTest` 鎖定 resolver 的欄位優先與文字 fallback 行為。
- `MatchEffectService` 改為委派下列邏輯：
  - `resolveSearchCount(...)`
  - `resolveSearchLookTopCount(...)`
  - `resolveSearchSourceZone(...)`
  - `resolveActionCount(...)`
  - `resolveReturnToHandSourceZone(...)`
  - `usesGiftHolderStackSnapshotForReturnToHand(...)`
- 從 `MatchEffectService` 移除 search / action count 相關正則與私有解析方法。

## 行為邊界

本次只搬移純解析邏輯，不改變：

- SEARCH 實際候選載入 SQL。
- RETURN_TO_HAND / RETURN_TO_DECK_TOP 的移動 SQL。
- pending decision payload schema。
- dice condition 判斷。
- SearchCriteria 解析規則。

## 大檔尺寸變化

- `MatchEffectService.java`：`11,949` -> `11,845` 行，減少 `104` 行。
- `MatchCardSelectionRequestResolver.java`：新增 `114` 行。
- `MatchCardSelectionRequestResolverTest.java`：新增 `72` 行。

## 驗證結果

已通過：

- `./mvnw -q -Dtest=MatchCardSelectionRequestResolverTest test`
- `./mvnw -q -Dtest=MatchCardSelectionSummaryBuilderTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#playSupportSearchShouldMoveMatchingCardFromDeckToHand,MatchActionServiceIntegrationTest#playSupportSearchShouldCreateDeckBottomReorderInteractionAndResolveInSpecifiedOrder,MatchActionServiceIntegrationTest#playSupportLookTopDeckShouldExposePendingInteractionWithCardContext test`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#bloomShouldTriggerReturnToHandEffectFromPassiveText test`
- `git diff --check`

補充：

- sandbox 內跑整合測試會因 Docker / PostgreSQL 權限被擋，已提高權限後驗證通過。

## 下一步建議

下一段可繼續處理 selection probe family：

- 抽出 search / return / return-to-deck-top 的 `SelectionProbe` 建構。
- 讓 pending decision 建立流程更靠近候選查詢與 resolver。
- 再評估是否把 SEARCH / RETURN execution 搬到專用 service。
