# 205-MatchEffect Damage Refactor Protection Test Acceptance Review

更新日期：2026-05-25

## 結論

本批先補 `DAMAGE` 重構前的 focused characterization test，尚未搬移 production code。原因是 `DAMAGE` 仍是目前 `MatchEffectService` 內風險最高的 effect family，直接抽 service 會同時牽動目標解析、傷害修正、HP 判定、down event、life loss 與 gift follow-up。

## 範圍

本批新增：

- `MatchEffectDamageExecutionCharacterizationTest`

目前先鎖住：

- raw text `ダメージN` 可解析成傷害值並套用到目標 Holomem。
- non-down summary payload 保留 `damageRequested`、`damageApplied`、`baseDamage`、`damageModifierApplied`、`targetBaseHp`、`targetHp`、`targetDamageTaken`、`downed`、`lifeReduced`。
- `match_turn_effects` 的 damage modifier 可把傷害修正到 0，且不更新 `damage_taken`。
- 缺少傷害值時回傳 no-op summary，且不更新 `damage_taken`。

本批不處理：

- 抽出 `MatchDamageEffectExecutionService`。
- `DAMAGE` 的 down / life loss / gift follow-up 實作搬移。
- 公開 API、資料庫 migration、seed data。

## 行數變化

- `MatchEffectService.java`：維持 `8,651` 行。
- 新增 `MatchEffectDamageExecutionCharacterizationTest.java`：`244` 行。

## 驗證結果

已執行：

```bash
./mvnw -q -Dtest=MatchEffectDamageExecutionCharacterizationTest test
```

結果：

- Focused characterization unit test 通過。

## 下一步建議

下一批建議繼續補 `DAMAGE` 的 down path 保護，至少包含：

- 傷害造成 CENTER down 時歸檔 attached cheer / support / bloom stack。
- CENTER down 會觸發 life loss。
- `deferDownEvent = true` 時保留 down event preview，但不立即套用預設 life loss。
- 特殊傷害被 immunity 擋下時不更新 `damage_taken`。

上述測試補齊後，再抽 `MatchDamageEffectExecutionService`，避免把高風險戰鬥規則在測試不足時一次搬移。
