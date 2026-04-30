# Play Card Action Log Payload Builder Acceptance Review

日期：2026-04-30
狀態：已完成
範圍：PLAY_CARD legacy action log payload cleanup

---

## 一、目標

本步延續 `127-MatchAction Pending Payload Cleanup Batch Acceptance Review.md` 後的新一批 cleanup，優先處理 `PLAY_CARD` 舊入口仍保留的 inline action payload / legacy action type glue。

目標是讓 `MatchActionService.playToStage(...)` 繼續維持 adapter 責任，但不再直接組裝 legacy action log payload。

本步不改：

- `PLAY_CARD` validator / resolver / follow-up 規則
- legacy API input / response timing
- action payload key
- legacy action type 值
- pending decision schema
- event / trigger dispatch 順序

---

## 二、完成項目

新增 `PlayCardActionLogPayloadBuilder`，承接：

- `cardInstanceId`
- `cardId`
- `targetZone`
- `enteredTurn`
- `faceDown`
- `idempotencyKey`
- `triggerSummary`
- MAIN placement 的 `giftEffect`
- MAIN placement 的 `triggerResolutionOrder`
- MAIN placement 的 pending interaction fields
- legacy action type resolution：
  - `OPENING_SET_CENTER`
  - `OPENING_SET_BACK`
  - `PLAY_TO_STAGE`

`MatchActionService.playToStage(...)` 改為委派 builder，保留：

- action 建立
- application service validate / resolve
- match phase touch
- effect follow-up resolve
- event dispatch
- append action 呼叫

---

## 三、Allow / Block 清單

### Allow

- 移出純 action log payload 組裝。
- 移出 legacy action type ternary 判斷。
- 補小型 unit test 保護 payload key 與 opening / main phase 差異。

### Block

- 不改 `PlayCardAction` shape。
- 不改 `PlayCardResolutionResult` shape。
- 不改 `PlayCardEffectResolution` shape。
- 不改 `OPENING_SET_CENTER` / `OPENING_SET_BACK` / `PLAY_TO_STAGE` 相容值。
- 不改 RESET opening setup 的 Gift / pending omission 規則。
- 不改 MAIN placement 的 Gift / pending inclusion 規則。

---

## 四、測試與驗證

已通過：

- `./mvnw -q -Dtest=PlayCardActionLogPayloadBuilderTest test`
- `./mvnw -q -Dtest=PlayCardActionLogPayloadBuilderTest,PlayCardEventFactoryTest,PlayCardApplicationServiceTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、結論

本步無 blocker。

`MatchActionService.playToStage(...)` 的 legacy action log payload glue 已抽為可測 builder，PLAY_CARD 舊入口仍維持 adapter 相容層，未回收 validator / resolver / follow-up 責任。

下一步建議繼續看 PLAY_CARD lifecycle glue：優先評估 `matches.current_phase` touch / save 是否能收斂成小型 helper，或先確認 action log append 是否有跨 use case 共用抽離價值。
