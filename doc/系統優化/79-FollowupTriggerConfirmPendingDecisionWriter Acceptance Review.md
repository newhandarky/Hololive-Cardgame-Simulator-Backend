# FollowupTriggerConfirmPendingDecisionWriter Acceptance Review

更新日期：2026-04-29
結論：通過，無 blocker

---

## 一、Review 範圍

本次 review 對照：

- `22-BLOOM Pilot Acceptance.md`
- `31-COLLAB Pilot Acceptance.md`
- `47-PLAY_CARD Pilot Acceptance.md`
- `23-BLOOM Pilot Acceptance Review.md`
- `32-COLLAB Pilot Acceptance Review.md`
- `48-PLAY_CARD Pilot Acceptance Review.md`
- `FollowupTriggerConfirmPendingDecisionWriter`
- `BloomEffectResolutionService`
- `PlayCardEffectResolutionService`
- `CollabEffectResolutionService`

目標是確認 AAA-44 將 Bloom / PlayCard / Collab 的 trigger confirm pending decision writer 共用化後：

- pending decision persistence 欄位不退步
- context JSON shape 不退步
- 舊入口 allow / block 清單仍成立
- 各 use case 的 message / cards / trigger payload 邊界仍留在各自 service
- 沒有把 use case 規則塞進共用 writer

---

## 二、完成條件檢查

| 項目 | 狀態 | 說明 |
| --- | --- | --- |
| 共用 writer 建立 | PASS | 新增 `FollowupTriggerConfirmPendingDecisionWriter`，集中 blocking pending 檢查、min/max 推導、SQL insert、JSON serialization。 |
| 共用 input 建立 | PASS | 新增 `FollowupTriggerConfirmPendingDecisionInput`，由呼叫端明確傳入 source action、effect type、title、message、cards 與 context。 |
| BLOOM 接線 | PASS | `BloomEffectResolutionService` 改用共用 writer，仍傳入 `BLOOM` / `BLOOM_EFFECT` / `確認 Bloom 效果`。 |
| PLAY_CARD 接線 | PASS | `PlayCardEffectResolutionService` 改用共用 writer，仍傳入 `GIFT` / `GIFT_TRIGGER` / `確認 Gift 效果`。 |
| COLLAB 接線 | PASS | `CollabEffectResolutionService` 改用共用 writer，仍傳入 `COLLAB` / `COLLAB_TRIGGER` / `確認連動觸發效果`。 |
| 舊專用 writer 移除 | PASS | 三個 use case 專屬 writer / test / decision record 已移除。 |
| 回傳型別收斂 | PASS | 三條路徑都回到 package-private `FollowupInteractionDecision`。 |
| focused test | PASS | 新增 `FollowupTriggerConfirmPendingDecisionWriterTest` 保護 insert args、context JSON 與 blocking pending。 |

---

## 三、Allow / Block 清單

### 已允許暫留

- `BloomEffectResolutionService` 保留 Bloom preview、message、source card payload 與 `sourceLevelType` context 組裝。
- `PlayCardEffectResolutionService` 保留 enter hook、Gift preview、Gift trigger payload、selection context 與 source card payload 組裝。
- `CollabEffectResolutionService` 保留 Collab preview、Gift preview、triggerSections、selection context 與 source card payload 組裝。
- 三條 service 仍各自 new 共用 writer；暫不改成 Spring bean，避免擴大 DI 變更。
- pending decision 仍寫入既有 `match_pending_decisions` table。
- legacy action log / response payload glue 仍保留在各 adapter 入口。

### 已確認未做

- 未改 `decision_type = TRIGGER_EFFECT_CONFIRM`。
- 未改 `status = PENDING`。
- 未改 `source_action_type`：
  - BLOOM：`BLOOM`
  - PLAY_CARD Gift：`GIFT`
  - COLLAB：`COLLAB`
- 未改 `effect_type`：
  - BLOOM：`BLOOM_EFFECT`
  - PLAY_CARD Gift：`GIFT_TRIGGER`
  - COLLAB：`COLLAB_TRIGGER`
- 未改 `context_json` 的基本欄位：
  - `interactionType`
  - `sourceActionType`
  - `title`
  - `message`
  - `cards`
  - `turnNumber`
- 未把 use case 規則判斷搬進共用 writer。
- 未改 pending decision 建立時機。
- 未改 event / trigger dispatch order。
- 未改舊入口 adapter allow / block 清單。

---

## 四、舊入口 Allow / Block 對照

### BLOOM

`22-BLOOM Pilot Acceptance.md` 允許 effect preview / pending interaction compatibility glue 暫留。

本次共用化後：

- pending persistence 不再是 BLOOM 專用 writer，但仍由 `BloomEffectResolutionService` 決定是否建立。
- `MatchActionService.bloom(...)` 未重新取得 validation / mutation / pending 建立責任。
- BLOOM resolver 仍沒有自行建立 pending interaction。

判定：通過。

### PLAY_CARD

`47-PLAY_CARD Pilot Acceptance.md` 禁止 enter hook / Gift follow-up 建立混在舊主方法。

本次共用化後：

- Gift pending decision 仍由 `PlayCardEffectResolutionService` 協調。
- `MatchActionService.playToStage(...)` 未重新取得 Gift pending 建立責任。
- RESET opening setup 不建立 Gift confirm 的邊界未改。

判定：通過。

### COLLAB

`31-COLLAB Pilot Acceptance.md` 禁止 collab / gift preview 與 confirm pending interaction 建立混在舊主方法。

本次共用化後：

- Collab / Gift pending decision 仍由 `CollabEffectResolutionService` 協調。
- `MatchActionService.moveStageHolomem(... targetZone=COLLAB)` 未重新取得 pending 建立責任。
- triggerSections 與 Gift selection context 仍在 Collab service 組裝。

判定：通過。

---

## 五、測試覆蓋

Focused unit：

- `FollowupTriggerConfirmPendingDecisionWriterTest`
  - insert args
  - source action normalization
  - min/max selection 推導
  - context JSON 基本欄位
  - blocking pending decision rejection
- `BloomEffectResolutionServiceTest`
- `PlayCardEffectResolutionServiceTest`
- `CollabEffectResolutionServiceTest`

Compile：

- `./mvnw -q -DskipTests compile`

Static：

- `git diff --check`

---

## 六、測試缺口

目前沒有 blocker。

可後續補強：

1. 加一個共用 writer 的 BLOOM 固定 `minSelect = 0` / `maxSelect = 0` focused case。
2. 加一個共用 writer 的 null `cards` / null `additionalContext` focused case。
3. 重跑代表性 legacy API integration smoke：
   - BLOOM trigger confirm path
   - PLAY_CARD Gift stage enter confirm path
   - COLLAB collab + Gift confirm path
4. 若後續把 writer 改成 Spring bean，需補 constructor wiring / context load smoke。

上述缺口不阻擋本階段，因為本次 cleanup 不改建立時機、不改 SQL 欄位、不改各 use case context 組裝；focused unit 與三個 effect resolution service tests 已覆蓋主要接線。

---

## 七、結論

`FollowupTriggerConfirmPendingDecisionWriter` 共用化通過 acceptance review。

下一步建議：

- 先做 code review / commit checkpoint。
- 後續可評估是否清理三個 effect resolution service 的 source card payload helper；若要做，應先盤點 payload shape 是否完全一致，再一條一條切，不要直接抽過寬的 follow-up framework。
