# 209-MatchEffect Passive Gift Incoming Damage Reduction Resolver Extraction Acceptance Review

更新日期：2026-05-26

## 結論

本批完成 `DAMAGE` 周邊 passive Gift incoming damage reduction resolver extraction。`MatchPassiveGiftIncomingDamageReductionResolverService` 接手 passive Gift 受傷減免的 target/holder 查詢、來源等級限制、受益者條件解析、骰子條件減免與 turn usage 紀錄，`MatchEffectCombatModifierService` 不再回呼 `MatchEffectService` 執行這段規則。

## 範圍

本批新增：

- `MatchPassiveGiftIncomingDamageReductionResolverService`
- `MatchPassiveGiftIncomingDamageReductionResolverServiceTest`

本批調整：

- `MatchEffectCombatModifierService.resolvePassiveGiftIncomingDamageReduction(...)` 改為委派 resolver。
- `MatchEffectCombatModifierService` 建立 resolver 所需的 `GiftTriggerMatcher`、`SearchCriteriaParser`、`GiftTurnUsageReader` 與 `PassiveGiftTriggerActionWriter`。
- 移除 `MatchEffectService` 內原本的 passive Gift incoming damage reduction target context、holder list loader、holder resolver、骰子條件 helper 與 turn usage writer callback。

本批不處理：

- DB migration。
- REST / WebSocket public API 契約。
- `MatchActionService` flow 重構。
- attached support incoming damage reduction resolver。
- passive Gift HP change prevention resolver。
- damage execution 的 down / life loss / no-op summary 行為調整。

## 行數變化

- `MatchEffectService.java`：`8,108` 行降至 `7,764` 行。
- `MatchEffectCombatModifierService.java`：`316` 行。
- 新增 `MatchPassiveGiftIncomingDamageReductionResolverService.java`：`561` 行。
- 新增 `MatchPassiveGiftIncomingDamageReductionResolverServiceTest.java`：`171` 行。

## 驗證結果

TDD 紅燈：

```bash
./mvnw -q -Dtest=MatchPassiveGiftIncomingDamageReductionResolverServiceTest test
```

結果：

- 初次執行因 `MatchPassiveGiftIncomingDamageReductionResolverService` 尚未存在而 test compile 失敗，符合預期紅燈。

已執行：

```bash
./mvnw -q -Dtest=MatchPassiveGiftIncomingDamageReductionResolverServiceTest test
./mvnw -q -Dtest=MatchPassiveGiftIncomingDamageReductionResolverServiceTest,AttackDamageServiceTest test
./mvnw -q -DskipTests compile
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldApplyOfficialPassiveGiftHsd07009DamageReductionOnCenter+attackArtShouldApplyOfficialPassiveGiftHbp05065DamageReductionFortyWhenDiceOdd+attackArtShouldApplyOfficialPassiveGiftHbp06009DamageReductionToOwnCollab+attackArtShouldApplyOfficialPassiveGiftHbp06082DamageReductionToAncientWeaponCenterWhenGuestOshiIsAnya test
```

結果：

- Focused resolver unit test 通過。
- `AttackDamageServiceTest` 通過，確認 attack damage modifier caller 保持相容。
- Java compile 通過。
- Focused passive Gift incoming damage reduction integration tests 通過；第一次沙盒內執行因 Docker / PostgreSQL socket 權限失敗，已提高權限後使用 Testcontainers PostgreSQL 重跑通過。

## 下一步建議

下一批若繼續處理 `DAMAGE` 周邊，建議拆 attached support incoming damage reduction resolver，集中 `match_holomem_supports` 查詢與支援卡減傷文案解析，讓 `MatchEffectCombatModifierService` 進一步只保留對戰修正值 facade 責任。

若要繼續降低 `MatchEffectService` 內戰鬥規則密度，也可以改拆 passive Gift HP change prevention resolver。該批會碰到「相手能力不能改變 HP」判斷，建議先補 focused test 鎖住 holder zone、target zone、level/tag/name 條件。
