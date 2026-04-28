# AttackArtApplicationService Acceptance Review

更新日期：2026-04-28
結論：通過，無 blocker

---

## 一、Review 範圍

本次 review 對照：

- `71-AttackArtApplicationService 第一版前置規劃.md`
- `72-AttackArtApplicationService Rest Phase Review.md`
- `AttackArtApplicationContext`
- `AttackArtApplicationResult`
- `AttackArtApplicationService`
- `AttackArtApplicationServiceTest`
- `MatchActionService.attackArt(...)` production adapter bridge

目標是確認 `AttackArtApplicationService` 第一版是否已完成：

- 建立 application service contract
- 固定 attack art middle pipeline 呼叫順序
- 接上 production adapter bridge
- 保留 `MatchActionService.attackArt(...)` 的前段 legality / loading 外殼
- 保留既有 payload / pending / action log / finish / enqueue 行為

---

## 二、完成條件檢查

| 項目 | 狀態 | 說明 |
| --- | --- | --- |
| application context | PASS | `AttackArtApplicationContext` 已承接 match / attacker / defender / art metadata。 |
| application result | PASS | `AttackArtApplicationResult` 回傳 stage results / payload / action log result / finish result。 |
| orchestration order | PASS | `AttackArtApplicationService.execute(...)` 固定 13 個 stage，focused test 覆蓋順序。 |
| previous stage result 傳遞 | PASS | resolver 可讀取 previous stage results，focused test 覆蓋。 |
| production bridge | PASS | `MatchActionService.attackArt(...)` 已改呼叫 `attackArtApplicationService.execute(...)`。 |
| legality / loading 外殼保留 | PASS | transaction、ActionContext、pending guard、turn action validation、phase validation、attacker/art loading 仍留在 `MatchActionService`。 |
| rest / phase 外殼評估 | PASS | 已於 `72-AttackArtApplicationService Rest Phase Review.md` 確認暫不再拆。 |
| life loss send cheer enqueue 保留 | PASS | 仍由 `MatchActionService.attackArt(...)` 在 application service 後執行。 |

---

## 三、Allow / Block 清單

### 已允許暫留

- `MatchActionService` 保留 transaction boundary。
- `MatchActionService` 保留 ActionContext loading / pending guard。
- `MatchActionService` 保留 turn required action validation。
- `MatchActionService` 保留 phase / first-turn legality。
- `MatchActionService` 保留 attacker row loading / attacker legality。
- `MatchActionService` 保留 art metadata loading。
- `MatchActionService` 保留 life loss send cheer enqueue。
- application service stage adapter 仍以 inner class 形式留在 `MatchActionService`，委派既有 helper / repository flow。

### 已確認未做

- 未改 payload key / payload shape。
- 未改 pending interaction timing。
- 未改 `ATTACK_ART` action log action type / writer / action order。
- 未改 finish check 順序。
- 未改 rest SQL 條件。
- 未改 phase transition timing。
- 未重寫單卡效果規則。
- 未把所有 SQL loading 搬入 application service。

---

## 四、測試覆蓋

Focused unit：

- `AttackArtApplicationServiceTest`
  - stage 呼叫順序
  - previous stage results 傳遞
  - payload / action log / finish result 回傳
  - null context guard

Integration baseline：

- `attackArtShouldApplyDamageToOpponentHolomemAndRestAttacker`
- `attackArtShouldTriggerOfficialGiftHbp02039WhenHoloxSlotRevealsSupport`
- `attackArtShouldTriggerOfficialGiftHbp02040WhenHoloxSlotRevealsSameBloomLevelMembers`
- `attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention`
- `attackArtShouldTriggerOfficialArtExtraEffectHbp01087AndDealSpecialDamageToAllOpponentBackHolomems`
- `attackArtShouldTriggerOfficialOshiHbp01007WhenBlueHolomemDamagesOpponentBack`
- `attackArtShouldTriggerDownedHolomemExtraLifeLoss`
- `attackArtShouldTriggerOfficialGiftHbp06014AndSwapHolopowerPickWithHandCard`

驗證命令：

- `./mvnw -q -Dtest=AttackArtApplicationServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldApplyDamageToOpponentHolomemAndRestAttacker+attackArtShouldTriggerOfficialGiftHbp02039WhenHoloxSlotRevealsSupport+attackArtShouldTriggerOfficialGiftHbp02040WhenHoloxSlotRevealsSameBloomLevelMembers+attackArtShouldTriggerOfficialGiftHbp01027WhenDamageReceivedAndApplyTurnOncePrevention+attackArtShouldTriggerOfficialArtExtraEffectHbp01087AndDealSpecialDamageToAllOpponentBackHolomems+attackArtShouldTriggerOfficialOshiHbp01007WhenBlueHolomemDamagesOpponentBack+attackArtShouldTriggerDownedHolomemExtraLifeLoss+attackArtShouldTriggerOfficialGiftHbp06014AndSwapHolopowerPickWithHandCard test`

---

## 五、測試缺口

目前沒有 blocker。

可後續補強：

1. `ATTACK_ART` payload snapshot，確認 application bridge 前後 key / shape 完全一致。
2. rest / phase / action log order 的 adapter-level assertion。
3. finish check 使用 `effectSummaryForChecks` 的 production-path assertion。
4. life loss send cheer enqueue 留在 application service 外的 direct assertion。
5. full attack no-special-effect smoke test，確認無特殊效果路徑只走 base stages。

上述缺口不阻擋本階段，因為 focused tests 已鎖住 application service order，broad integration baseline 已覆蓋主要 production path。

---

## 六、結論

`AttackArtApplicationService` 第一版已完成。

下一步建議：

- 先做 code review / commit checkpoint。
- 後續進入 attack pilot 收尾規劃：
  - 評估是否將 application stage adapter 從 `MatchActionService` 搬到獨立 factory / adapter class。
  - 評估是否補 payload snapshot tests。
  - 評估是否開始下一條 use case 或回頭補 attack 相關測試缺口。
