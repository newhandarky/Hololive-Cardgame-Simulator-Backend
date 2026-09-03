# BE-002：建立 ActionCapabilities

狀態：DONE
風險：中高
Repository：`hololive-cardgame-backend`
建議 commit：`後端：提供回合合法操作能力投影`
前置工作：BE-001（已完成）

## 一、目標

讓伺服器提供目前玩家可執行的回合操作，前端與 NPC 不再從 phase、recent actions 與卡片文字複製規則。

本 pilot 只涵蓋：

- `DRAW_TURN`
- `SEND_TURN_CHEER`
- `ADVANCE_PHASE`
- `END_TURN`

## 二、必讀

- `doc/系統優化/next/02-卡牌對戰引擎藍圖.md`
- `MatchActionService` 上述四個入口與 validator/helper。
- `MatchTurnLifecycleService`
- `MatchGameStateService`
- `GameStateResponse`
- 對應 integration tests。

## 三、範圍內

新增：

- `ActionCapability`
- `ActionCapabilityCode` 或 typed action enum
- `TurnActionCapabilityService`
- viewer projection mapping
- focused rule/capability tests

以 additive 欄位加入 `GameStateResponse.actionCapabilities`。舊 client 忽略欄位仍可運作。

建議資料：

```java
public record ActionCapability(
    MatchActionType type,
    boolean enabled,
    GameRuleCode reasonCode,
    Map<String, Object> constraints
) {}
```

若可行，`constraints` 也用 typed record；pilot 不需要為空資料建立動態 map。

## 四、非目標

- 不涵蓋 Bloom、攻擊、Support、Oshi、Baton Touch。
- 不改前端；前端採用由 FE-004 處理。
- 不把 capability 當作安全檢查替代品；command handler 仍需重新驗證。
- 不回傳中文 UI 文案。
- 不在 capability query 執行 mutation。

## 五、共用規則要求

capability 與 command validation 不得各寫一套判斷。可以：

- 從 `MatchActionService` 抽 pure rule/helper。
- 或建立 rule service，舊 action method 與 capability 同時委派。

至少共用：

- match status。
- current player。
- phase。
- blocking pending choice。
- draw/turn cheer completion。
- stage/cheer availability。

若某 rule 必須讀 DB，透過命名 query port/service，避免 capability service 複製 SQL。

## 六、執行步驟

1. 建立四個 action 的 decision table，從現有測試/實作確認每種 phase。
2. 補 characterization test，比對「capability enabled」與實際 command 是否可成功。
3. 抽共用 rule。
4. 建 viewer-specific capability service。
5. 加入 `GameStateResponse` additive 欄位。
6. 補 JSON contract test。
7. 確認 opponent projection 不提供 hidden target/candidate。

## 七、驗收

- 在 STARTED match 的每個 phase，四個 capability 有明確 enabled/reason。
- pending choice 存在時，一般 action disabled。
- 非當前玩家全部 disabled 或只回允許的非回合 action。
- capability enabled 的 fixture 執行同 action 不會因相同 precondition 被拒絕。
- capability disabled 不代表 client 可繞過 handler；直接 POST 仍被拒絕。
- `GameStateResponse` 既有欄位不變。

## 八、測試矩陣

至少：

- RESET/DRAW/CHEER/MAIN/PERFORMANCE/END。
- current/non-current player。
- blocking choice。
- 空 Cheer deck / 無 stage holomem。
- draw/cheer 已完成。
- finished match。

執行：

```bash
./mvnw -q -Dtest=<TurnActionCapabilityServiceTest> test
./mvnw -q -Dtest=<FocusedIntegrationTest> test
./mvnw -q -DskipTests compile
git diff --check
```

## 九、回滾

- 移除 additive DTO 欄位與 capability service。
- 共用 rule 若已被 action 採用且測試通過可保留；若要完整回滾，恢復 facade 委派。
- 無 DB migration。

## 十、2026-09-03 實際進度

已完成：

- 新增 `ActionCapability`、`ActionCapabilityCode`、`ActionCapabilityReasonCode`。
- 新增 `TurnActionCapabilityService` 與 `TurnActionRuleService`。
- 將 additive `actionCapabilities` 加入 viewer-specific `GameStateResponse`。
- `MatchActionService` 與 `EndTurnApplicationService` 已共用部分 status/phase/action/pending 規則。
- 9 個 `TurnActionCapabilityServiceTest` 與 8 個 `MatchControllerEndTurnApiIntegrationTest` 通過。
- Java compile 與 `git diff --check` 通過。
- capability enabled 與相同 fixture 的 draw/turn-cheer command success parity 已鎖定。
- 空 Cheer deck、無 stage Holomem、pending 與非當前玩家 contract 已鎖定。
- GitNexus detect-changes：7 個檔案、41 個符號、20 條流程，風險 `CRITICAL`；主因是共用 `loadActionContext` 與 viewer game-state projection 橫跨玩家、NPC 與回合流程，已由上述 focused tests 覆蓋 pilot contract。
- production/test commit：`e9e9a5b 後端：提供回合合法操作能力投影`。

後續：

- `sendTurnCheer` 建立 pending interaction 時仍會查詢 Cheer source 與 target；本批以整合契約鎖定，單一 fact/query ownership 列為 BE-007 `SEND_TURN_CHEER` vertical slice 的第一刀。
