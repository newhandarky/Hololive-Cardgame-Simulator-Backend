# 212-MatchEffect Passive Gift Art Bonus Resolver Extraction Acceptance Review

更新日期：2026-05-26

## 結論

本批完成 passive Gift art bonus resolver extraction。`MatchPassiveGiftArtBonusResolverService` 接手 passive Gift 藝能加成的 target context 查詢、CENTER / COLLAB holder 查詢、holder 文案解析、特殊傷害加成、holder / target 條件、attached support 條件、對手場上 tag 條件與歷史推し技能條件解析。

`MatchEffectCombatModifierService.resolvePassiveGiftArtBonus(...)` 保留 public 入口，但改為委派新 resolver；`MatchEffectService` 不再承接 passive Gift 藝能加成規則。

## 範圍

本批新增：

- `MatchPassiveGiftArtBonusResolverService`
- `MatchPassiveGiftArtBonusResolverServiceTest`

本批調整：

- `MatchEffectCombatModifierService` 建立 `MatchPassiveGiftArtBonusResolverService` 欄位。
- `resolvePassiveGiftArtBonus(...)` 改為委派 resolver。
- 移除 `MatchEffectService` 內原本的 `StaticArtBonusTargetContext`、target loader、art bonus holder resolver、特殊傷害加成解析與對手場上 tag 條件 helper。

本批保留：

- `MatchEffectService.loadPassiveGiftArtBonusHolderContexts(...)` 仍保留給 passive Gift art cost reduction 使用。
- `MatchEffectService` 內 art cost reduction、art text damage bonus、damage down / life loss flow 不變。

本批不處理：

- DB migration。
- REST / WebSocket public API 契約。
- `MatchActionService` flow 重構。
- passive Gift art cost reduction resolver。
- damage execution 的 no-op summary、damage_taken、down、life loss 行為調整。

## 行數變化

- `MatchEffectService.java`：`7,494` 行降至 `7,260` 行。
- `MatchEffectCombatModifierService.java`：`289` 行。
- 新增 `MatchPassiveGiftArtBonusResolverService.java`：`629` 行。
- 新增 `MatchPassiveGiftArtBonusResolverServiceTest.java`：`192` 行。

## 驗證結果

TDD 紅燈：

```bash
./mvnw -q -Dtest=MatchPassiveGiftArtBonusResolverServiceTest test
```

結果：

- 初次執行因 `MatchPassiveGiftArtBonusResolverService` 尚未存在而 test compile 失敗，符合預期紅燈。

已執行：

```bash
./mvnw -q -Dtest=MatchPassiveGiftArtBonusResolverServiceTest test
./mvnw -q -Dtest=MatchPassiveGiftArtBonusResolverServiceTest,AttackDamageServiceTest test
./mvnw -q -Dtest=MatchDamageEffectExecutionServiceTest test
./mvnw -q -Dtest=MatchEffectDamageExecutionCharacterizationTest test
./mvnw -q -DskipTests compile
```

結果：

- Focused passive Gift art bonus resolver unit test 通過。
- Attack damage focused unit test 通過，確認 combat modifier 入口委派後仍維持攻擊傷害加成輸出。
- Damage execution focused unit test 通過。
- Damage characterization test 通過。
- Java compile 通過。

Focused integration：

```bash
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldApplyOfficialPassiveGiftHbp06046ArtBonusAfterReferencedSpOshiSkillUsedThisGame test
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldNotApplyOfficialPassiveGiftHbp06046ArtBonusWithoutReferencedSpOshiSkillHistory test
```

沙盒內結果：

- 兩個 integration test 初次執行都因 Docker / PostgreSQL socket 權限失敗。
- 主要錯誤為 `Operation not permitted`，Testcontainers 無法連 `/var/run/docker.sock`，fallback local PostgreSQL 也因 socket 權限無法連線。

提權重跑：

```bash
./mvnw -q '-Dtest=MatchActionServiceIntegrationTest#attackArtShouldApplyOfficialPassiveGiftHbp06046ArtBonusAfterReferencedSpOshiSkillUsedThisGame+attackArtShouldNotApplyOfficialPassiveGiftHbp06046ArtBonusWithoutReferencedSpOshiSkillHistory' test
```

結果：

- 通過。
- Testcontainers PostgreSQL 正常啟動並完成兩個 HBP06-046 passive Gift art bonus integration cases。

## 下一步建議

下一批建議拆 passive Gift art cost reduction resolver，延續本批邊界：

- 把 `loadPassiveGiftArtBonusHolderContexts(...)` 從 `MatchEffectService` 最後搬出。
- 集中 `PassiveGiftArtCostReductionTargetContext`、art name 條件、target attached support 條件與 cheer color cost reduction 解析。
- 保留 `MatchEffectCombatModifierService.resolvePassiveGiftArtCheerCostReduction(...)` public 入口，只改為委派新 resolver。

建議先補 focused unit test，再跑 HBP05-013 / HSD03-008 類 art cost integration 或現有命名聚焦 case，避免和 action flow 重構混在同批。
