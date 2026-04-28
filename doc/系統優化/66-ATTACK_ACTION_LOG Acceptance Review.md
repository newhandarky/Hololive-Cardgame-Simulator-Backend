# ATTACK_ACTION_LOG Acceptance Review

更新日期：2026-04-28
結論：通過，無 blocker

---

## 一、Review 範圍

本次 review 對照：

- `65-ATTACK Pilot 啟動規劃.md`
- `AttackActionLogContext`
- `AttackActionLogResult`
- `AttackActionLogService`
- `AttackActionLogServiceTest`
- `MatchActionService.attackArt(...)` adapter bridge

目標是確認 `ATTACK_ACTION_LOG` 第一版是否已完成：

- 固定 `ATTACK_ART` action type
- 接收 match / user / turn / payload JSON
- payload JSON 原樣送入 writer
- 透過 adapter 保留既有 action order / repository 寫入方式
- `attackArt(...)` 改呼叫 action log service

本次不驗收 payload 組裝、attacker rest、phase transition、finish check、pending decision creation 或 life loss send cheer enqueue 拆分。

---

## 二、完成條件檢查

| 項目 | 狀態 | 說明 |
| --- | --- | --- |
| 使用 `ATTACK_ART` action type | PASS | `AttackActionLogService.ACTION_TYPE_ATTACK_ART` 固定為 `ATTACK_ART`，focused test 覆蓋 writer 收到的 action type。 |
| 接收 match / user / turn / payload | PASS | `AttackActionLogContext` 包含 `matchId`、`userId`、`turnNumber`、`payloadJson`。 |
| payload 原樣送入 writer | PASS | service 不解析也不修改 payload JSON，focused test 覆蓋 passthrough。 |
| 回傳 action metadata | PASS | `AttackActionLogResult` 回傳 action id / action order / action type / payload JSON。 |
| adapter bridge | PASS | `MatchActionService.attackArt(...)` 已改呼叫 `AttackActionLogService.appendAttackArt(...)`。 |
| 保留 action order 計算方式 | PASS | `AttackArtActionWriter` 委派 `MatchActionService.appendAction(matchId, ...)`，仍使用 `findMaxActionOrderByTurn(...) + 1`。 |

---

## 三、Allow / Block 清單

### 已允許暫留

- `appendAction(...)` 共用寫入邏輯仍留在 `MatchActionService`。
- 非 attack art 的 action log 呼叫點仍走既有 `appendAction(...)`。
- action log service 目前由 `MatchActionService` 直接建立，透過 inner writer 委派既有 repository 寫入。
- payload 組裝仍由 `AttackRestAndPayloadService` 提供。
- finish check 與 enqueue interaction 仍留在 `MatchActionService.attackArt(...)`。

### 已確認未做

- 未改 action type。
- 未改 payload JSON 內容。
- 未改 action order 計算方式。
- 未改 turn number / user id。
- 未改 attacker rest timing。
- 未改 finish check evaluation order。
- 未改 pending decision creation。

---

## 四、測試覆蓋

Focused unit：

- `AttackActionLogServiceTest`
  - 缺少 context 時拒絕
  - 使用 `ATTACK_ART` action type
  - payload 原樣送入 writer
  - turn number / user id 原樣送入 writer

Integration baseline：

- `attackArtShouldTriggerDownedHolomemExtraLifeLoss`
- `attackArtShouldTriggerOfficialGiftHbp06014AndSwapHolopowerPickWithHandCard`
- `attackArtShouldTriggerOfficialGiftHbp06027AndGrantExtraBloomAllowance`

驗證命令：

- `./mvnw -q -Dtest=AttackActionLogServiceTest test`
- `./mvnw -q -DskipTests compile`
- `./mvnw -q -Dtest=MatchActionServiceIntegrationTest#attackArtShouldTriggerDownedHolomemExtraLifeLoss+attackArtShouldTriggerOfficialGiftHbp06014AndSwapHolopowerPickWithHandCard+attackArtShouldTriggerOfficialGiftHbp06027AndGrantExtraBloomAllowance test`

---

## 五、測試缺口

目前沒有 blocker。

可後續補強：

1. production action log snapshot assertion：確認 `ATTACK_ART` action order 與 payload key 在整合測試中完全一致。
2. 多 action 同 turn 時 action order 遞增的 attack art 專用 assertion。
3. writer adapter 回傳 action id / order 的 production-path assertion。

上述缺口不阻擋本階段，因為 action log service 已由 focused tests 鎖定 contract，代表性 attack art production path 已由 integration baseline 覆蓋。

---

## 六、結論

`ATTACK_ACTION_LOG` 第一版已完成。

下一步建議進入：

- `ATTACK_FINISH_CHECK` 前置拆分

目標是把 `attackArt(...)` 尾段的 card effect finish / life defeat / no holomem defeat 順序收斂成 focused contract，先固定勝負判定呼叫順序，再評估完整 `AttackArtApplicationService`。
