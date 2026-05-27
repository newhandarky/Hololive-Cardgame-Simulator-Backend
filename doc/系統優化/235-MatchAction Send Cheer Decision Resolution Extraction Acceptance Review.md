# AAA-235 MatchAction Send Cheer Decision Resolution Extraction Acceptance Review

日期：2026-05-27

## Summary

本批延續 AAA-233 / AAA-234 的 decision lifecycle 主線，將 `SEND_CHEER` pending decision resolution 從 `MatchActionService` 搬入 package-private `MatchDecisionResolutionService`。

此次只搬移既有 orchestration 行為，不改 REST / WebSocket public API、不新增 DB migration，也不改 Cheer 附加規則語意。

## Scope

- `MatchDecisionResolutionService`
  - 新增 `SEND_CHEER` decision type handling。
  - 搬入 selected target sanitize / min-max / candidate validation。
  - 搬入 target Holomem 查詢、來源 Cheer 卡查詢與 zone / cheer card 驗證。
  - 搬入 `SendCheerAction` execution 與 failure reason handling。
  - 搬入 pending resolved、phase update、`INTERACTION_CONFIRMED` action log。
  - 保留回合 Cheer 特例：source action 為 `TURN_CHEER` 時附加 main step gift follow-up，並額外寫入 `TURN_CHEER` action。

- `MatchActionService`
  - `resolveDecision(...)` 不再直接分支處理 `SEND_CHEER`。
  - 移除原本 `resolveSendCheerDecision(...)` private helper。
  - 移除 `SendCheerInteractionPayloadBuilder` 欄位與 `SendCheerAction` import。

- `MatchDecisionResolutionServiceTest`
  - 補 `SEND_CHEER` focused unit test：
    - turn cheer pending decision 成功 resolve 後會執行 `GameActionExecutor`。
    - mark pending resolved。
    - phase 回到 `MAIN`。
    - append main step gift follow-up。
    - 依序寫入 `INTERACTION_CONFIRMED` 與 `TURN_CHEER` action log。

## File Size

- `src/main/java/com/hololive/cardgame/service/MatchActionService.java`：`5,250` 行。
- `src/main/java/com/hololive/cardgame/service/MatchDecisionResolutionService.java`：`515` 行。
- `src/test/java/com/hololive/cardgame/service/MatchDecisionResolutionServiceTest.java`：`238` 行。

## Verification

已執行：

```bash
./mvnw -q -Dtest=MatchDecisionResolutionServiceTest test
./mvnw -q -DskipTests compile
./mvnw -q '-Dtest=MatchActionServiceIntegrationTest#resolveDecisionShouldAttachCheerForSendCheerInteraction' test
```

結果：

- `MatchDecisionResolutionServiceTest`：通過。
- `compile`：通過。
- `MatchActionServiceIntegrationTest#resolveDecisionShouldAttachCheerForSendCheerInteraction`：
  - 沙盒內首次執行因 Docker / PostgreSQL socket `Operation not permitted` 無法建立測試 datasource。
  - 使用相同 Maven command 提權後，Testcontainers PostgreSQL 啟動成功，測試通過。

## Acceptance

- `SEND_CHEER` resolution 已由 `MatchDecisionResolutionService` 承接。
- 原本 `MatchActionService` 內的 `resolveSendCheerDecision(...)` 已移除。
- 回合 Cheer 成功 resolve 的 phase、pending status、Cheer attachment 與 action log 行為由 focused unit test 與 integration test 保護。

## Next Step

下一批建議不要直接合併 `TRIGGER_EFFECT_CONFIRM` 與 `CARD_SELECTION`，可先處理以下其中一個方向：

- 低風險：搬移 `LIVE_START` decision resolution，並評估 `TURN_START` 是否需要先抽 return-collab lifecycle helper。
- 中風險：抽出共用 selected-card validation helper，讓後續 `TRIGGER_EFFECT_CONFIRM` / `CARD_SELECTION` 搬移時不再複製 sanitize / min-max / candidate validation。
