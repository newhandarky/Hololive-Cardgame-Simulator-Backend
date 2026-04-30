# Play Card Cleanup Batch Acceptance Review

日期：2026-04-30
狀態：已完成
範圍：PLAY_CARD legacy adapter cleanup batch 收束

---

## 一、目標

本文件收束 `128` 至 `130` 的 PLAY_CARD cleanup batch。

本批目標是讓 `MatchActionService.playToStage(...)` 保留 legacy adapter / orchestration 入口，但移出 action log payload、phase finalization、action log writer 等細節責任。

本批不改：

- `PLAY_CARD` validator / resolver / effect follow-up 規則
- legacy API request DTO
- legacy action type 值
- action payload key
- `match_actions` schema
- action order 計算規則
- pending decision schema
- event / trigger dispatch 順序

---

## 二、完成項目

### action log payload

- `PlayCardActionLogPayloadBuilder`
  - 組裝 legacy action log payload。
  - 保留 RESET opening setup 不輸出 Gift / pending fields。
  - 保留 MAIN placement 輸出 Gift / trigger resolution / pending fields。
  - 保留 legacy action type：
    - `OPENING_SET_CENTER`
    - `OPENING_SET_BACK`
    - `PLAY_TO_STAGE`

### phase lifecycle glue

- `PlayCardMatchPhaseFinalizer`
  - RESET opening setup -> `RESET`
  - MAIN placement -> `MAIN`
  - 呼叫 `MatchTimestampService.touchUpdatedAt(...)`
  - 呼叫 `MatchRepository.saveAndFlush(...)`

### action log writer

- `PlayCardActionLogWriter`
  - 呼叫 payload builder。
  - 使用 `MatchPayloadJsonService` 序列化 payload。
  - 使用 `MatchActionRepository.findMaxActionOrderByTurn(...) + 1`。
  - 寫入 `MatchActionEntity`。

---

## 三、目前 `playToStage(...)` 邊界

目前舊入口仍保留：

- legacy `PlayToStageActionRequest` 解析。
- `ActionContext` 載入。
- pending interaction gate。
- `PlayCardAction.fromApi(...)` adapter。
- application service validate / resolve。
- phase finalizer 呼叫。
- effect resolution 呼叫。
- event dispatch 呼叫。
- action log writer 呼叫。

已移出：

- 主要 validation。
- state mutation。
- enter hook / Gift follow-up。
- event 建立與 trigger dispatch 分類。
- action log payload 組裝。
- phase touch / save 細節。
- action log JSON serialization / append 細節。

---

## 四、Allow / Block 清單

### Allow

- 保留舊入口作為 adapter / orchestration。
- 保留 `loadActionContext(...)` 與 pending gate 在舊入口。
- 保留 request DTO -> `PlayCardAction` 轉換。
- 保留依序協調 application / effect / event / action log 的流程。

### Block

- 不把 source / target validation 搬回舊入口。
- 不把 `match_cards` / `match_holomems` / stack mutation 搬回舊入口。
- 不把 Gift pending decision 建立搬回舊入口。
- 不改 legacy action log shape。
- 不改 RESET opening setup phase 行為。
- 不改 MAIN placement follow-up 行為。

---

## 五、測試與驗證

本批已分步執行 focused tests：

- `PlayCardActionLogPayloadBuilderTest`
- `PlayCardMatchPhaseFinalizerTest`
- `PlayCardActionLogWriterTest`
- `PlayCardApplicationServiceTest`
- `PlayCardEventFactoryTest`

每步亦已執行：

- `./mvnw -q -DskipTests compile`
- `git diff --check`

本批最後驗證：

- `./mvnw -q -Dtest=PlayCardActionLogWriterTest,PlayCardActionLogPayloadBuilderTest,PlayCardMatchPhaseFinalizerTest,PlayCardApplicationServiceTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 六、剩餘缺口

無 blocker。

可接受暫留：

- `playToStage(...)` 仍使用 legacy DTO 與 `ActionContext`。
- `playToStage(...)` 仍是 legacy API 入口與 orchestration。
- full `MatchActionServiceIntegrationTest` 仍有既有廣泛失敗，需獨立穩定化。

後續可做：

- 轉向下一個 legacy lifecycle cleanup。
- 或重新盤點下一條 use case，避免 PLAY_CARD cleanup batch 繼續擴散。

---

## 七、結論

本批 PLAY_CARD cleanup 通過 batch acceptance review。

`MatchActionService.playToStage(...)` 已退到可接受的 adapter / orchestration 層級；後續若再動 PLAY_CARD，應有新的明確目標或測試缺口，不建議在本批繼續追加無邊界 cleanup。
