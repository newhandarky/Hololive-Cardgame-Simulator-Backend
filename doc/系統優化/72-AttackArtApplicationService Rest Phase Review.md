# AttackArtApplicationService Rest / Phase Review

更新日期：2026-04-28
結論：暫不再拆，進入 acceptance review

---

## 一、Review 範圍

本次 review 對照：

- `71-AttackArtApplicationService 第一版前置規劃.md`
- `AttackArtApplicationService`
- `MatchActionService.attackArt(...)` production adapter bridge
- `AttackApplicationRestAndPayloadResolver`
- `AttackApplicationActionLogResolver`
- `AttackApplicationFinishCheckResolver`

目標是確認 AAA-2 把 rest / phase / action log / finish check 納入 application stage adapter 後，是否需要先拆成更小 service，或可直接進 AAA-4 acceptance review。

---

## 二、目前狀態

`MatchActionService.attackArt(...)` 目前保留：

- transaction boundary
- `ActionContext` loading
- pending interaction guard
- turn required action validation
- phase / first-turn legality
- attacker row loading
- attacker zone / rest / per-turn usage legality
- art metadata loading
- life loss send cheer enqueue

`AttackArtApplicationService` stage adapter 目前接管：

- pre-damage follow-up
- cost
- target
- damage
- damage prevention
- damage application
- post-damage follow-up
- down
- defender Gift follow-up
- post-trigger pending
- attacker rest DB update
- phase transition / save
- rest and payload
- `ATTACK_ART` action log
- finish check

這比 AAA-0 的保守規劃更進一步，但仍保留最容易受 API / transaction / request validation 影響的前段外殼。

---

## 三、Rest / Phase 評估

### attacker rest DB update

目前位於 `AttackApplicationRestAndPayloadResolver`。

判斷：

- 仍使用原 SQL 條件：
  - `id`
  - `match_id`
  - `owner_user_id`
  - `is_rested = FALSE`
- 仍在 payload 組裝前執行。
- 失敗訊息維持原本的「藝能結算失敗，請重新整理後再試」。
- broad attack integration baseline 已覆蓋 vanilla attack rest。

結論：

- 暫不再拆。
- 若未來要拆，應建立 `AttackRestStateService` 或將 rest update 放進專門 stage service，但不阻塞 AAA-4。

### phase transition / save

目前位於 `AttackApplicationRestAndPayloadResolver`。

判斷：

- 仍在 rest 成功後執行。
- 仍設定 `currentPhase = PERFORMANCE`。
- 仍呼叫 `touchUpdatedAt(match)`。
- 仍呼叫 `matchRepository.saveAndFlush(match)`。
- 仍在 `ATTACK_ART` action log 前執行，與舊流程一致。

結論：

- 暫不再拆。
- 若未來要拆，建議建立 `AttackPhaseTransitionService`，但要先加 phase/action log order snapshot。

---

## 四、Action Log / Finish 評估

### `ATTACK_ART` action log

目前位於 `AttackApplicationActionLogResolver`。

判斷：

- payload 來源仍是 `AttackRestAndPayloadResult.payload()`。
- action log 寫入仍委派 `AttackActionLogService.appendAttackArt(...)`。
- action order / repository 寫入仍由原 writer adapter 處理。
- 仍在 rest / phase / payload 後寫入。

結論：

- 暫不再拆。
- acceptance review 應確認 payload shape 不變，以及 action log timing 沒有提前。

### finish check

目前位於 `AttackApplicationFinishCheckResolver`。

判斷：

- `effectSummaryForChecks` 仍來自 `AttackRestAndPayloadResult.effectSummaryForChecks()`。
- finish check 仍委派 `AttackFinishCheckService.resolve(...)`。
- 仍在 `ATTACK_ART` action log 後執行。
- life loss send cheer enqueue 仍保留在 `MatchActionService.attackArt(...)` 最後。

結論：

- 暫不再拆。
- acceptance review 應確認 life loss send cheer enqueue 沒有被移入 application service。

---

## 五、測試缺口

目前沒有 blocker。

AAA-4 acceptance review 前可補或至少列入缺口：

1. `ATTACK_ART` action log payload snapshot。
2. rest / phase / action log order 的 adapter-level assertion。
3. finish check 使用 `effectSummaryForChecks` 的 production-path assertion。
4. life loss send cheer enqueue 仍在 application service 外的 assertion。

現階段 broad integration baseline 已覆蓋：

- vanilla damage / rest
- Holox / HBP02
- damage prevention
- official art extra / Oshi reactive
- down / extra life loss
- post-trigger pending

---

## 六、結論

AAA-2 的 rest / phase / action log / finish stage adapter 可接受，暫不需要再拆更小 service。

下一步建議進入：

- AAA-4 acceptance review

重點是檢查：

- `attackArt(...)` 主流程複雜度是否已降低
- allow / block 清單是否仍成立
- production 行為是否已由 focused tests + integration baseline 覆蓋
