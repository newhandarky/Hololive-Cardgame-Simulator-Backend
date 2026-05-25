# 206-MatchEffect Damage Effect Execution Service Extraction Acceptance Review

更新日期：2026-05-25

## 結論

本批完成 `DAMAGE` effect execution service extraction。`MatchEffectService.executeDamageEffect(...)` 保留原 package-private 入口與 damage redirect 分支，其餘一般傷害、特殊傷害 no-op、傷害寫入、down 歸檔、life loss 與 summary 組裝改由 `MatchDamageEffectExecutionService` 負責。

## 範圍

本批新增：

- `MatchDamageEffectExecutionService`
- `MatchDamageEffectExecutionServiceTest`

本批補強：

- `MatchEffectDamageExecutionCharacterizationTest` 增加 down path、deferred down event preview、特殊傷害免疫 no-op 保護。

本批不處理：

- DB migration。
- REST / WebSocket public API 契約。
- `MatchActionService` flow 重構。
- 特殊傷害免疫、effective HP snapshot、passive gift HP / damage reduction 的進一步 service 拆分。

## 行數變化

- `MatchEffectService.java`：`8,651` 行降至 `8,432` 行。
- 新增 `MatchDamageEffectExecutionService.java`：`438` 行。
- `MatchEffectDamageExecutionCharacterizationTest.java`：`481` 行。
- 新增 `MatchDamageEffectExecutionServiceTest.java`：`165` 行。

## 驗證結果

已執行：

```bash
./mvnw -q -Dtest=MatchDamageEffectExecutionServiceTest test
./mvnw -q -Dtest=MatchEffectDamageExecutionCharacterizationTest test
./mvnw -q -DskipTests compile
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldApplyDamageToOpponentHolomemAndRestAttacker test
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldTriggerOfficialExtraLifeLossForHbp03083WhenSelfDowned+attackArtShouldTriggerOfficialExtraLifeLossForHbp05028WhenSelfDowned+specialDamageShouldTriggerOfficialGiftHsd13012AndPreventBackDamageThisTurn+specialDamageShouldNotTriggerOfficialGiftHsd13012WithoutStackCost test
git diff --check
```

結果：

- Focused service unit test 通過。
- Damage characterization test 通過。
- Java compile 通過。
- Focused integration test 通過；第一次沙盒內執行因 Docker / PostgreSQL socket 權限失敗，已提高權限後使用 Testcontainers PostgreSQL 重跑通過。
- diff whitespace 檢查通過。

## 下一步建議

下一批若繼續處理 `DAMAGE`，建議以單一高耦合 resolver 為界線：

- effective HP snapshot resolver
- special damage prevention resolver
- passive gift HP / incoming damage reduction resolver

每批仍應先補 focused test，再搬 production code。
