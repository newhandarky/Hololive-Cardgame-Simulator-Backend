# Play Card Match Phase Finalizer Acceptance Review

日期：2026-04-30
狀態：已完成
範圍：PLAY_CARD match phase lifecycle glue cleanup

---

## 一、目標

本步延續 `128-Play Card Action Log Payload Builder Acceptance Review.md`，處理 `MatchActionService.playToStage(...)` 內仍保留的 PLAY_CARD phase touch / save glue。

目標是讓 legacy adapter 不再直接操作：

- `match.currentPhase`
- `updatedAt`
- `matchRepository.saveAndFlush(...)`

本步不改：

- RESET opening setup 仍維持 `RESET`
- MAIN placement 仍維持 / 回到 `MAIN`
- `PlayCardAction` / `PlayCardResolutionResult` shape
- effect follow-up / event dispatch 順序
- action log payload 或 action type

---

## 二、完成項目

新增 `PlayCardMatchPhaseFinalizer`，承接：

- 依 `PlayCardResolutionResult.openingReset()` 決定目標 phase
  - `true` -> `RESET`
  - `false` -> `MAIN`
- 呼叫 `MatchTimestampService.touchUpdatedAt(...)`
- 呼叫 `MatchRepository.saveAndFlush(...)`

`MatchActionService.playToStage(...)` 改為呼叫：

- `playCardMatchPhaseFinalizer.finalizePhase(resolutionResult)`

---

## 三、Allow / Block 清單

### Allow

- 移出 PLAY_CARD 專屬 phase finalization glue。
- 補 unit test 保護 RESET / MAIN phase 決策。
- 補 unit test 保護 timestamp touch 與 repository flush 順序。

### Block

- 不改 `PlayCardActionResolver` persistence mutation。
- 不改 `PlayCardEffectResolutionService` 觸發時機。
- 不把通用 `transitionMatchToMainAndSave(...)` 擴大替換。
- 不改其他 use case 的 phase touch / save 行為。

---

## 四、測試與驗證

已通過：

- `./mvnw -q -Dtest=PlayCardMatchPhaseFinalizerTest,PlayCardActionLogPayloadBuilderTest,PlayCardApplicationServiceTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、結論

本步無 blocker。

`MatchActionService.playToStage(...)` 的 phase lifecycle glue 已抽成可測 helper，PLAY_CARD 舊入口仍保留 adapter orchestration，但不再直接負責 phase finalization 細節。

下一步建議繼續看 `playToStage(...)` 剩餘 adapter glue：優先評估 append action / JSON serialization 是否要做 PLAY_CARD 專屬 writer，或確認此方法已可暫時收束並轉向下一個 legacy lifecycle cleanup。
