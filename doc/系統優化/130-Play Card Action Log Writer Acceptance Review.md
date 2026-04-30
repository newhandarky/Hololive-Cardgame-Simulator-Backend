# Play Card Action Log Writer Acceptance Review

日期：2026-04-30
狀態：已完成
範圍：PLAY_CARD legacy action log writer cleanup

---

## 一、目標

本步延續 `129-Play Card Match Phase Finalizer Acceptance Review.md`，處理 `MatchActionService.playToStage(...)` 剩餘的 action log append / JSON serialization glue。

目標是讓 `playToStage(...)` 不再直接呼叫：

- `appendAction(...)`
- `toJson(payload)`
- legacy action log payload builder

本步不改：

- legacy action type 值
- action payload key
- action order 計算規則
- `match_actions` persistence schema
- PLAY_CARD validator / resolver / follow-up / event dispatch

---

## 二、完成項目

新增 `PlayCardActionLogWriter`，承接：

- 呼叫 `PlayCardActionLogPayloadBuilder.buildPayload(...)`
- 呼叫 `PlayCardActionLogPayloadBuilder.resolveLegacyActionType(...)`
- 使用 `MatchPayloadJsonService.toJson(...)` 序列化 payload
- 使用 `MatchActionRepository.findMaxActionOrderByTurn(...) + 1`
- 寫入 `MatchActionEntity`

`MatchActionService.playToStage(...)` 改為呼叫：

- `playCardActionLogWriter.appendPlayCardAction(action, resolutionResult, effectResolution)`

---

## 三、Allow / Block 清單

### Allow

- 移出 PLAY_CARD 專屬 action log writer。
- 保留 legacy action type / payload 相容。
- 補 unit test 保護 repository 寫入欄位、action order 與 JSON payload。

### Block

- 不改通用 `MatchActionService.appendAction(...)`。
- 不改其他 use case 的 action log 寫入。
- 不改 `PlayCardActionLogPayloadBuilder` payload shape。
- 不改 `MatchPayloadJsonService` 行為。

---

## 四、測試與驗證

已通過：

- `./mvnw -q -Dtest=PlayCardActionLogWriterTest,PlayCardActionLogPayloadBuilderTest,PlayCardMatchPhaseFinalizerTest,PlayCardApplicationServiceTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、結論

本步無 blocker。

`MatchActionService.playToStage(...)` 已不再直接負責 PLAY_CARD action log payload、JSON serialization 或 action append 細節，剩餘內容主要是 legacy request adapter 與 orchestration。

下一步建議收束 PLAY_CARD cleanup batch acceptance review；若無缺口，再轉向下一個 legacy lifecycle cleanup 或重新盤點下一條 use case。
