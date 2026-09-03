# BE-007：遷移回合 Command Vertical Slices

狀態：IN_PROGRESS
風險：中
Repository：`hololive-cardgame-backend`
前置工作：BE-001、BE-002
建議系列 commit：`後端：遷移 <action> 對戰指令流程`

## 一、目標

把 BE-001 的 `MatchCommandGateway` 從 `CONCEDE` pilot 擴展到四個已具備 ActionCapabilities 的回合操作：

- `DRAW_TURN`
- `SEND_TURN_CHEER`
- `ADVANCE_PHASE`
- `END_TURN`

本工作包不是一次搬完四條流程。每個 action 是一個獨立 vertical slice；完成一條、驗證一條、提交一條，再開始下一條。

## 二、為什麼現在做

- `MatchActionService` 約有 75 個依賴欄位，繼續抽 helper 會讓它成為更大的 delegate hub。
- BE-001 已建立 gateway seam，BE-002 已建立四個 action 的共用 rule/capability 基礎。
- 這四條 action 的契約與測試相對清楚，適合成為 ownership migration 模板。
- PendingChoice、state version 與 NPC 後續都需要穩定 command boundary。

## 三、執行順序

建議依風險：

1. `DRAW_TURN`
2. `SEND_TURN_CHEER`
3. `ADVANCE_PHASE`
4. `END_TURN`

`END_TURN` 已有 `EndTurnApplicationService`，但仍需接入統一 command envelope/result；不要與其他三條同批修改。

## 四、每條 slice 的範圍

新增或調整：

- typed `MatchCommand`。
- 對應 `MatchCommandHandler`。
- controller/gateway wiring。
- command/capability 共用 rule/fact。
- 必要的 query port/service。
- focused unit、application 與 REST integration test。
- legacy facade delegate 或刪除已搬移 orchestration。

完成後必須能指出：

- 從 `MatchActionService` 移除了哪些方法責任或 dependency。
- 哪些 SQL/rule 不再重複。
- controller/public contract 是否保持不變。

## 五、非目標

- 不同批導入 stateVersion/command receipt table；由 BE-005 處理。
- 不統一全部 PendingChoice；由 BE-003 處理。
- 不修改 effect/timing 語意。
- 不改前端 UI。
- 不做全量 package move。
- 不把四個 action 合成一個大型 `TurnCommandHandler`。

## 六、設計約束

### Handler

- 接收 typed command，不讀 HTTP DTO。
- transaction/lock 邊界只有一個 owner。
- capability 與 handler 使用相同 rule/fact，不靠複製 if/SQL 保持一致。
- handler 回傳 typed result/events，不回傳 `ResponseEntity` 或自由 `Map` 作核心結果。

### Legacy facade

- 可短期保留 public method 作 adapter，但不得保留完整 orchestration 的雙入口。
- 若 facade 只委派，需標記移除條件與仍存在的 caller。
- 每完成一條 action，`MatchActionService` 的 dependency/ownership 必須不增加。

### 測試

- capability enabled → 相同 fixture command 可成功。
- capability disabled → 直接提交 command 仍被 server 拒絕。
- pending/phase/current player/duplicate precondition 具代表性案例。
- 既有 action log、pending interaction、phase transition 與 WebSocket projection 語意保持。

## 七、單一 slice 步驟

1. GitNexus context/impact 目標 public method 與 controller endpoint。
2. 鎖定現有 REST、state、action log 與 pending 行為。
3. 建 typed command/handler 與 rule/fact 介面。
4. 讓 gateway dispatch 新 handler。
5. facade 改為薄 adapter，或讓 controller 不再依賴該入口。
6. 刪除已搬移的重複 rule/SQL/orchestration。
7. 執行 focused unit/integration/compile。
8. GitNexus detect changes，確認只影響預期 action flows。
9. 更新本工作包 checkpoint 並獨立 commit。

## 八、每條驗收

- REST path/request/response/status 不變。
- command 與 capability parity tests 通過。
- transaction/lock/publish 不重複執行。
- 不新增新的 `MatchActionService` feature dependency。
- 至少移除一段 legacy orchestration、重複規則或 SQL。
- focused action flow 與相鄰 phase/pending regression 通過。
- 有明確 rollback：controller/gateway 可切回前一個已驗證入口。

## 九、驗證模板

```bash
./mvnw -q -Dtest=<CommandHandlerTest>,<CapabilityParityTest> test
./mvnw -q -Dtest=<FocusedApiIntegrationTest> test
./mvnw -q -DskipTests compile
git diff --check
npx gitnexus detect-changes --repo Hololive-Cardgame-Simulator-Backend --scope all
```

實際測試名稱以 repository 現況為準，不填造不存在的方法。

## 十、完成定義

四個 action 各自完成獨立 commit 後，本工作包才標 `DONE`。若只完成部分，狀態記為 `IN_PROGRESS`，並在下表記錄：

| Action | 狀態 | Commit | Legacy responsibility removed |
| --- | --- | --- | --- |
| DRAW_TURN | DONE | `f0cbfb1` | controller、Hard NPC 與 test support 改走 typed command；從 `MatchActionService` 移除交易入口、抽牌 SQL、pending 建立協調、action payload 與 128 行 legacy orchestration |
| SEND_TURN_CHEER | DONE | `ae59019` | controller、Hard NPC 與 test support 改走 typed command；移除 `MatchActionService.sendTurnCheer` orchestration、pending source/target 重複查詢與 NPC 重複 SQL |
| ADVANCE_PHASE | TODO |  |  |
| END_TURN | TODO |  |  |

## 十一、DRAW_TURN checkpoint（2026-09-03）

- 新增 `DrawTurnCommand`、`DrawTurnCommandHandler` 與 `DrawTurnApplicationService`；公開 REST path/request/response 保持不變。
- capability 與 command 共用 `TurnActionRuleService` 的 active/started/current-player/phase/pending/duplicate facts。
- `MatchActionService` 不再暴露 `drawTurn`，也不保留抽牌 SQL 或雙入口；既有內部 dependency 數沒有增加。
- `MatchTurnLifecycleService` 統一寫入 DRAW_TURN、DRAW_REVEAL pending 與 DRAW_DECK_OUT 結算。
- 驗證：4 個 handler/application unit tests、9 個 `MatchControllerEndTurnApiIntegrationTest`、2 個正常抽牌/deck-out focused integration tests、compile、test-compile 與 diff check 通過。
- GitNexus staged detect-changes：21 files、33 symbols、17 flows、CRITICAL；主因是 controller/Hard NPC 與共用 integration test support 皆切換 command 入口，已由 REST、capability、pending、正常抽牌與 deck-out gates 覆蓋。

## 十二、SEND_TURN_CHEER checkpoint（2026-09-03）

- 新增 `SendTurnCheerCommand`、`SendTurnCheerCommandHandler` 與 `SendTurnCheerApplicationService`；公開 REST path/request/response 與 `TURN_CHEER` publish event 保持不變。
- 新增 `TurnCheerAvailabilityService`，將 Cheer source 與 stage target 收旂為單一 fact；`TurnActionRuleService`、command 與 Hard NPC 共用同一份 capability/availability 結果。
- `PendingDecisionCreationService` 使用已解析 availability 建立 `SEND_CHEER` payload，不再重新查詢回合 Cheer source/target。
- `MatchActionService` 移除 30 行 send-turn-cheer 交易入口與 orchestration；`HardNpcService` 移除 39 行重複 Cheer deck/stage SQL。
- 有 pending interaction 時改為明確拒絕 `PENDING_INTERACTION_BLOCKED`，與 `DRAW_TURN` 及 capability contract 一致；未改 DB schema、REST DTO 或 effect/timing 語意。
- 驗證：handler/application/availability/pending/capability focused tests 20 個、REST integration 11 個、Hard NPC integration 8 個、gateway 4 個，及 send-turn-cheer/phase focused integration 2 個均通過；`test-compile` 與 diff check 通過。
- GitNexus staged detect-changes：26 files、41 symbols、21 flows、CRITICAL；主因是 controller、Hard NPC、pending 與共用 turn rule 切換入口，已由 REST parity、NPC、pending、capability 與 phase gates 覆蓋。
- Rollback：可單獨 revert `ae59019`，回到已驗證的 `MatchActionService.sendTurnCheer` 入口；本 slice 沒有 migration 或資料回填。
