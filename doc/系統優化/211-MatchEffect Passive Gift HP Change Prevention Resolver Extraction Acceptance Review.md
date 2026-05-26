# 211-MatchEffect Passive Gift HP Change Prevention Resolver Extraction Acceptance Review

更新日期：2026-05-26

## 結論

本批完成 passive Gift HP change prevention resolver extraction。`MatchPassiveGiftHpChangePreventionResolverService` 接手「相手能力不能改變 HP」的回合狀態檢查、target context 查詢、holder list 查詢、holder zone 限制與 target 條件解析，`DAMAGE` 與 `HEAL` execution 不再透過 `MatchEffectService` callback 執行這段規則。

## 範圍

本批新增：

- `MatchPassiveGiftHpChangePreventionResolverService`
- `MatchPassiveGiftHpChangePreventionResolverServiceTest`

本批調整：

- `MatchEffectService` 建立 `MatchPassiveGiftHpChangePreventionResolverService` 欄位。
- `MatchDamageEffectExecutionService` 與 `MatchHealEffectExecutionService` 的 HP change blocker callback 改為委派 resolver。
- 移除 `MatchEffectService` 內原本的 `PassiveGiftHpChangePreventionTargetContext`、`MatchTurnContext`、target loader、holder loader、holder resolver 與 blocker callback。

本批不處理：

- DB migration。
- REST / WebSocket public API 契約。
- `MatchActionService` flow 重構。
- passive Gift art bonus / art cost reduction resolver。
- damage execution 的 down / life loss / no-op summary 行為調整。

## 行數變化

- `MatchEffectService.java`：`7,717` 行降至 `7,494` 行。
- 新增 `MatchPassiveGiftHpChangePreventionResolverService.java`：`297` 行。
- 新增 `MatchPassiveGiftHpChangePreventionResolverServiceTest.java`：`148` 行。

## 驗證結果

TDD 紅燈：

```bash
./mvnw -q -Dtest=MatchPassiveGiftHpChangePreventionResolverServiceTest test
```

結果：

- 初次執行因 `MatchPassiveGiftHpChangePreventionResolverService` 尚未存在而 test compile 失敗，符合預期紅燈。

已執行：

```bash
./mvnw -q -Dtest=MatchPassiveGiftHpChangePreventionResolverServiceTest test
./mvnw -q -Dtest=MatchPassiveGiftHpChangePreventionResolverServiceTest,MatchHealEffectExecutionServiceTest,MatchDamageEffectExecutionServiceTest test
./mvnw -q -Dtest=MatchEffectDamageExecutionCharacterizationTest test
./mvnw -q -DskipTests compile
```

結果：

- Focused HP change prevention resolver unit test 通過。
- HEAL / DAMAGE execution focused unit tests 通過，確認 blocker callback 換線後 summary 行為維持。
- Damage characterization test 通過。
- Java compile 通過。
- 目前未找到既有 `MatchActionServiceIntegrationTest` 內針對「相手能力不能改變 HP」的命名聚焦 case；本批以 focused unit 與 damage characterization 保護。

## 下一步建議

下一批若繼續降低 `MatchEffectService` 的戰鬥規則密度，建議拆 passive Gift art bonus / art cost reduction resolver，集中常駐 Gift 的藝能加成與藝能費用減免解析。

該區塊仍牽涉 holder zone、target zone、名稱、tag、attached support、歷史推し技能 / 藝能使用條件，建議先補 focused test，再分批搬移 art bonus 與 art cost reduction，避免單 commit 同時改兩套輸出型別。
