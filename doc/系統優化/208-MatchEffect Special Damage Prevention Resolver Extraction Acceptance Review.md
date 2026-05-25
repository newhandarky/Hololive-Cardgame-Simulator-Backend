# 208-MatchEffect Special Damage Prevention Resolver Extraction Acceptance Review

更新日期：2026-05-25

## 結論

本批完成 `DAMAGE` special damage prevention resolver extraction。`MatchSpecialDamagePreventionResolverService` 接手 SPECIAL_DAMAGE_IMMUNITY turn effect 查詢與 HSD13-012 stack cost 防護啟用，`MatchDamageEffectExecutionService` 不再透過 `MatchEffectService` callback 執行特殊傷害防護規則。

## 範圍

本批新增：

- `MatchSpecialDamagePreventionResolverService`
- `MatchSpecialDamagePreventionResolverServiceTest`

本批調整：

- `MatchEffectService` 建立並注入 `MatchSpecialDamagePreventionResolverService`。
- `MatchDamageEffectExecutionService` 的特殊傷害防護 callback 改為委派 resolver。
- 移除 `MatchEffectService` 內原本的 `tryActivateHsd13012SpecialDamageImmunity(...)`、`archiveOneStackCardFromHolder(...)`、`isSpecialDamageImmunityActive(...)`。

本批不處理：

- DB migration。
- REST / WebSocket public API 契約。
- `MatchActionService` flow 重構。
- passive gift incoming damage reduction resolver。
- damage execution 的 down / life loss / no-op summary 行為調整。

## 行數變化

- `MatchEffectService.java`：`8,306` 行降至 `8,108` 行。
- 新增 `MatchSpecialDamagePreventionResolverService.java`：`310` 行。
- 新增 `MatchSpecialDamagePreventionResolverServiceTest.java`：`227` 行。

## 驗證結果

已執行：

```bash
./mvnw -q -Dtest=MatchSpecialDamagePreventionResolverServiceTest,MatchDamageEffectExecutionServiceTest,MatchEffectDamageExecutionCharacterizationTest test
./mvnw -q -DskipTests compile
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#specialDamageShouldTriggerOfficialGiftHsd13012AndPreventBackDamageThisTurn test
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#specialDamageShouldNotTriggerOfficialGiftHsd13012WithoutStackCost test
git diff --check
```

結果：

- Focused special damage prevention resolver unit test 通過。
- Damage execution service unit test 通過。
- Damage characterization test 通過。
- Java compile 通過。
- Focused integration tests 通過；第一條第一次沙盒內執行因 Docker / PostgreSQL socket 權限失敗，已提高權限後使用 Testcontainers PostgreSQL 重跑通過，第二條直接提高權限使用 Testcontainers PostgreSQL 跑通過。
- diff whitespace 檢查通過。

## 下一步建議

下一批若繼續處理 `DAMAGE`，建議拆 passive gift incoming damage reduction resolver，集中一般受傷減免與藝能減傷解析。

該區塊仍屬戰鬥規則熱區，應維持單批只搬一個 resolver，先補 focused test 再改 production code。
