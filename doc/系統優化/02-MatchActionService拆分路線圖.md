# MatchActionService 拆分路線圖

更新日期：2026-04-24
定位：`MatchActionService` 專用重構規劃
用途：將對戰指令主流程拆成可維護的 orchestration modules。

---

## 一、現況摘要

`MatchActionService` 目前同時處理：

- 玩家 action 入口
- opening / mulligan / setup
- phase 推進
- pending decision / interaction resolve
- support / bloom / collab / attack / baton touch
- defeat / draw / match finish
- 一部分 action payload 組裝
- 一部分直接 SQL 操作

這個檔案的問題不是只有長，而是每一個公開方法都會牽動：

- turn state
- pending state
- effect engine
- match action history
- game state payload

因此它比較像「遊戲回合協調器 + 各種規則 handler 的混合體」。

---

## 二、本階段不做的事

- 不一次把所有 action 都做成獨立 command class
- 不在第一輪重寫 `attackArt(...)`
- 不直接移除 `MatchActionService` facade
- 不先改前端 request / response 結構

---

## 三、拆分原則

### 1. 先拆流程模組，不先拆設計模式

這裡若直接上完整 Command Pattern，很容易產生：

- 一堆 handler 仍共享相同巨大 helper 群
- `resolveDecision(...)` 類流程仍要回頭互相呼叫
- 只是把 God Class 平鋪成多個耦合類

所以第一步要拆的是流程邊界。

### 2. `resolveDecision(...)` 優先級極高

這段現在同時管：

- pending decision 讀取
- 每種 interaction type 的分支
- confirm 後 effect 套用
- phase 切換與 follow-up payload

若不先拆它，後續任何互動式效果都還是會繼續往 God Class 堆。

### 3. phase flow 應集中

目前 `drawTurn`、`sendTurnCheer`、`advancePhase`、opening setup、turn end 彼此高度耦合，應抽成同一個 turn lifecycle 模組。

---

## 四、階段拆分

## Phase A1：整理 ActionContext / PendingDecision 載入責任

目標：

- 將 context loading、pending blocking、共用前置檢查集中

建議新服務：

- `MatchActionContextService`

責任：

- 載入 `ActionContext`
- 檢查 phase / turn ownership
- 檢查 pending 是否阻擋操作
- 查 opponent / turnNumber / match 狀態

這一步先減少各公開 action 方法重複的前置驗證。

進度：

- AAA-231 已先抽出 `PendingDecisionStore` 與 top-level `PendingDecision`，將 `resolveDecision(...)` 的 pending `FOR UPDATE` 載入、context JSON 映射與 resolved 更新移出 `MatchActionService`。
- AAA-232 已抽出 `PendingDecisionCreationService`，集中 `TURN_START`、`LIVE_START`、`DRAW_REVEAL`、`SEND_CHEER`、`CARD_SELECTION` 建立流程，作為 A2 之前的第二個地基。
- 下一批可開始 A2，但第一刀建議只搬低耦合 look / reorder decision handler，避免把 trigger confirm、support selection、send cheer 與 turn lifecycle 一次混進 service extraction。

## Phase A2：抽出 Decision Resolution Service

目標：

- 把 `resolveDecision(...)` 從 God Class 抽成專門協調器

建議新服務：

- `MatchDecisionResolutionService`

責任：

- 載入 pending decision
- 依 decisionType 分派
- 驗證選牌數量與候選範圍
- 執行 confirm 後的 effect / phase / payload 更新

建議再切內部子 handler：

- `TurnStartDecisionHandler`
- `TriggerEffectConfirmDecisionHandler`
- `SendCheerDecisionHandler`
- `LookDecisionHandler`
- `CardSelectionDecisionHandler`

這裡的 handler 只需先存在於同 package，不需要一開始就做通用框架。

## Phase A3：抽出 Turn / Phase Lifecycle Service

目標：

- 把開局、抽牌、回合 cheer、phase 推進、live start、performance start/end 的主流程收斂

建議新服務：

- `MatchTurnFlowService`

責任：

- `mulligan`
- `drawTurn`
- `sendTurnCheer`
- `advancePhase`
- opening setup progression
- performance phase snapshots / related triggers

配套：

- phase transition payload 的共用 builder
- `create pending interaction` 的 helper 集中

## Phase A4：抽出 Board Action Service

目標：

- 將不含攻擊主傷害結算的場面動作先分出去

建議新服務：

- `MatchBoardActionService`

責任：

- `playToStage`
- `bloom`
- `playSupport`
- `moveStageHolomem`
- `batonTouch`
- `attachCheer`

注意：

- 這一階段仍可保留 `attackArt` 在原 service
- 因為 `attackArt` 是最重、最危險的動作主流程

## Phase A5：抽出 Attack Resolution Service

目標：

- 將 `attackArt(...)` 與其相關 helper 抽成專門攻擊協調器

建議新服務：

- `MatchAttackResolutionService`

責任：

- 藝能費用驗證
- target resolve
- bonus / reduction / redirect
- art damage 套用
- down event follow-up
- attacker / defender trigger preview / apply
- 攻擊後收尾與勝負判定

這一步一定要獨立 commit、獨立驗證，不能混其他重構。

## Phase A6：決定是否升級成 Action Handler Registry

當以下條件成立時，才考慮完整 command / handler registry：

1. action 公開入口已大致只剩 facade + dispatch
2. 各 action family 已有穩定協調服務
3. pending 與 phase lifecycle 已不再直接散落到各 action 方法

若未滿足，就先不要硬上 pattern。

---

## 五、建議 commit 節奏

每個 phase 建議拆成：

1. 抽服務與搬移方法
2. 保持 facade 對外 API
3. 補 targeted integration coverage
4. 最後清理命名與註解

---

## 六、與 HardNpcService 的關係

`HardNpcService` 目前大量依賴：

- `MatchActionService`
- `MatchGameStateService`
- pending interaction 決策格式

所以 NPC 不應先拆。正確順序是：

1. 先穩定 `MatchActionService` 的內部流程邊界
2. 再把 NPC 決策抽成：
   - `NpcPendingResolver`
   - `NpcTurnPlanner`
   - `NpcActionExecutor`

否則 NPC 只會跟著再重拆一次。

---

## 七、這一輪建議先做哪一步

建議順序：

1. `Phase A2` Decision Resolution Service
2. `Phase A3` Turn / Phase Lifecycle Service
3. `Phase A4` Board Action Service
4. `Phase A5` Attack Resolution Service

原因：

- `resolveDecision` 是目前最多互動式規則的匯流點
- `turn/phase` 是各 action 共享的協調邏輯
- `board actions` 可以先拆掉一大塊非攻擊流程
- `attackArt` 最重，應最後處理
