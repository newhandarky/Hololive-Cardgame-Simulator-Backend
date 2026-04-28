# ATTACK_TARGET Acceptance Review

更新日期：2026-04-28
定位：`ATTACK_TARGET` 前置拆分驗收 review
用途：對照 `51-ATTACK_TARGET 前置拆分規劃.md`，確認 attack art target 子流程是否已可視為 `ATTACK_DAMAGE` 前的穩定基準。

---

## 一、結論

`ATTACK_TARGET` 前置拆分目前可視為已通過階段性驗收。

理由：

1. target context / result / target contract 已存在。
2. requested target validation、default target priority、passive Gift target restriction 與 damage redirect 已集中到 `AttackTargetService`。
3. `MatchActionService.attackArt(...)` 已改成 adapter 呼叫 service，並保留既有 target payload shape。
4. redirect 前 defender self-downed snapshot 載入時機已透過 `targetBeforeRedirect` 保留。
5. focused tests 與 target / redirect integration baseline 已覆蓋主要行為。
6. 本步沒有改 attack cost、damage、down、life loss 或 Gift trigger timing。

---

## 二、完成條件對照

### 1. Contract / service skeleton

已完成：

- `AttackTargetContext`
- `AttackTargetHolomem`
- `AttackTargetResult`
- `AttackTargetService`
- `AttackTargetServiceTest`

目前 service 負責：

- query opponent Holomem count
- resolve requested target
- resolve default target priority
- resolve passive Gift target restriction to COLLAB
- resolve restriction auto target / explicit reject
- resolve one-shot damage redirect target
- return effective target and payload flags

判定：通過。

### 2. Adapter bridge

`MatchActionService.attackArt(...)` 目前保留：

- cost service 呼叫
- defender self-downed snapshot 載入
- damage bonus / reduction
- critical target color 判斷
- damage apply
- down / life loss
- Gift follow-up
- legacy action log payload

已移出：

- opponent Holomem count
- requested target query
- default target priority
- passive Gift target restriction to COLLAB
- center tag condition check
- COLLAB auto target
- damage redirect target resolve / consume
- target helper records

目前 payload 仍保留：

- `targetCardInstanceId`
- `passiveGiftTargetRestrictionToCollab`
- `passiveGiftTargetRestrictionApplied`
- `damageRedirectApplied`
- `targetMainColor`

判定：通過。

---

## 三、舊入口 allow / block 清單對照

### 允許保留項

目前仍留在舊入口，且符合 `51` 文件允許範圍：

- `attackArt(...)` 仍是 `ATTACK_ART` 主流程入口。
- defender self-downed snapshots 仍留在 `MatchActionService`。
- damage bonus / reduction 仍留在 `MatchActionService`。
- critical target color 判斷仍留在 damage 計算區塊。
- payload 組裝仍留在 `MatchActionService`。
- damage redirect 仍在 target resolve 階段消耗，維持既有語意。

判定：通過。

### 不允許保留項

以下項目已不再位於舊入口主流程：

- target count / requested target validation 留在 `MatchActionService`。
- default target priority 留在 `MatchActionService`。
- passive Gift target restriction 留在 `MatchActionService`。
- damage redirect target resolve / consume 留在 `MatchActionService`。
- target payload flags 由分散條件直接組裝。
- 本前置拆分順手改 attack cost consume。
- 本前置拆分順手改 damage / down / life loss。
- 本前置拆分順手改 Gift trigger timing。
- 本前置拆分改 target payload key。

判定：通過。

---

## 四、測試覆蓋對照

### Focused unit tests

`AttackTargetServiceTest` 已覆蓋：

- 對手沒有 Holomem 時回傳 no-op target result
- passive restriction center tag 擷取
- passive restriction center tag 條件成立
- damage redirect target 消耗

判定：通過。

### Integration baseline

已跑過的 target / redirect integration baseline：

- `attackArtShouldRedirectDamageToPreparedReplacementTarget`
- `attackArtShouldAutoTargetOpponentCollabWhenOfficialGiftHbp01050RestrictsArtTarget`
- `attackArtShouldRejectCenterTargetWhenOfficialGiftHbp01050RestrictsArtTarget`
- `attackArtShouldRejectCenterTargetWhenOfficialGiftHbp05010ConditionIsMet`
- `attackArtShouldAllowCenterTargetWhenOfficialGiftHbp05010CenterTagConditionNotMet`
- `attackArtShouldRejectCenterTargetWhenOfficialGiftHbp05043ConditionIsMet`
- `attackArtShouldAllowCenterTargetWhenOfficialGiftHbp05043CenterTagConditionNotMet`
- `attackArtShouldApplyOfficialSpecialDamageBonusHbp05045WhenAttackerIsOkayuAndTargetIsCenter`

判定：通過。

---

## 五、測試缺口

目前沒有 blocker。

仍可後續補強：

1. requested target 可解析時的 focused JDBC mock test。
2. 未指定 target 時 default priority 的 focused JDBC mock test。
3. passive restriction 成立且 requested target 非 COLLAB 的 focused unit / JDBC mock test。
4. passive restriction 成立且未指定 target auto COLLAB 的 focused unit / JDBC mock test。

這些缺口已有 integration baseline 保護，不阻塞本次前置拆分驗收。

---

## 六、風險與暫留技術債

1. `ATTACK_ART` 主流程仍然很大。
   - 本次只拆 target 子流程，damage / down / Gift follow-up 尚未拆。
2. `AttackTargetService` 目前仍直接使用 JDBC。
   - 這符合前置拆分階段；若後續抽 repository/query bridge，可再收斂。
3. `targetBeforeRedirect` 是為了保留既有 snapshot timing。
   - 若未來重新定義 redirect 與 defender trigger timing，需要產品與規則判斷。

判定：可接受。

---

## 七、下一步建議

下一步建議進 `ATTACK_DAMAGE` 前置拆分。

建議順序：

1. 建立 `ATTACK_DAMAGE` 前置拆分規劃。
2. 先抽 damage modifier / reduction summary，不直接搬 down / life loss。
3. `ATTACK_DAMAGE` 驗收後，再評估 down / Gift follow-up 子流程。
