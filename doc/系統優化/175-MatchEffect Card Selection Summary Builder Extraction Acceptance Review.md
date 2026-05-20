# MatchEffect Card Selection Summary Builder Extraction Acceptance Review

日期：2026-05-20
狀態：已完成
主題：將 Search / Return 摘要組裝搬到 package-private builder

---

## 一、本次完成內容

- 新增 `MatchCardSelectionSummaryBuilder`。
- 新增 `MatchCardSelectionSummaryBuilderTest`。
- 將 `MatchEffectService` 內下列摘要組裝委派到新 builder：
  - `buildDeckBottomReorderCandidate(...)`
  - `buildSearchEffectSummary(...)`
  - `buildReturnToHandSummary(...)`
  - `buildReturnToDeckTopSummary(...)`
- `MatchEffectService` 保留 search / return 的 SQL 執行與流程協調。
- 本步不改 effect execution、candidate selection、pending decision schema 或前端 payload key。

---

## 二、影響檔案

- `src/main/java/com/hololive/cardgame/service/MatchCardSelectionSummaryBuilder.java`
- `src/test/java/com/hololive/cardgame/service/MatchCardSelectionSummaryBuilderTest.java`
- `src/main/java/com/hololive/cardgame/service/MatchEffectService.java`
- `doc/系統優化/00-系統優化總覽.md`
- `doc/系統優化/05-重構進度追蹤.md`

---

## 三、尺寸變化

- `MatchEffectService.java`：`12,050` -> `11,949` 行，減少 `101` 行。
- `MatchCardSelectionSummaryBuilder.java`：新增 `151` 行。
- `MatchCardSelectionSummaryBuilderTest.java`：新增 `148` 行。

---

## 四、驗證結果

已通過：

- `./mvnw -q -Dtest=MatchCardSelectionSummaryBuilderTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#playSupportSearchShouldMoveMatchingCardFromDeckToHand,MatchActionServiceIntegrationTest#playSupportSearchShouldCreateDeckBottomReorderInteractionAndResolveInSpecifiedOrder,MatchActionServiceIntegrationTest#playSupportLookTopDeckShouldExposePendingInteractionWithCardContext test`

驗證環境注意：

- sandbox 內執行整合測試時，Docker socket 與本機 PostgreSQL 連線會被 `Operation not permitted` 阻擋。
- 提高權限後，Testcontainers PostgreSQL 可正常啟動並完成上述 targeted integration tests。

額外觀察：

- `MatchActionServiceIntegrationTest#bloomShouldTriggerReturnToHandEffectFromStructuredDefinition`
- `MatchActionServiceIntegrationTest#bloomShouldTriggerReturnToDeckTopEffectFromStructuredDefinition`

上述兩個測試在提高權限後可重現失敗，現象是 Bloom 建立 pending decision，Archive 目標卡未自動移動。這個失敗發生在 summary builder 被呼叫之前，與本次摘要搬移沒有直接因果；建議另開工作包釐清 Bloom structured return effect 的「需互動選牌 vs 自動套用」規則。

---

## 五、判讀

- 本步完成 Step AAA-167 後續建議的「把 search / return summary builders 搬成 package-private builder 類」。
- `MatchEffectService` 的 effect 執行區塊少掉純 payload 組裝噪音。
- 新 builder 將前端與 pending interaction 依賴的摘要 key 固定在 unit test 中，後續搬 `MatchCardSelectionEffectService` 時有更小的回歸面。

---

## 六、下一步

建議繼續 `Search / Return / Look Family`，優先順序：

1. 抽出 search / return count 與 source zone resolver。
2. 抽出 selection probe builder。
3. 最後再搬 `executeSearchEffect(...)` / `executeReturnToHandEffect(...)` / `executeReturnToDeckTopEffect(...)` 到 `MatchCardSelectionEffectService`。
