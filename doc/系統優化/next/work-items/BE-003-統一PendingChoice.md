# BE-003：統一 PendingChoice

狀態：READY_AFTER_BE-007
風險：中高
Repository：`hololive-cardgame-backend`
建議 commit：`後端：建立統一待選擇續接模型`
前置工作：BE-001、BE-002、BE-007

## 一、目標

建立單一 domain `PendingChoice` 與 persistence adapter，先遷移 `LOOK_TOP_DECK` 的讀取/解決流程。保留目前 `pendingDecisions`、`pendingInteractions` API 形狀作相容 projection。

## 二、必讀

- `doc/系統優化/next/02-卡牌對戰引擎藍圖.md`
- `PendingDecision`、`PendingDecisionStore`、`PendingDecisionReader`
- `PendingDecisionCreationService`
- `MatchDecisionResolutionService`
- `MatchGameStateService` pending mapping
- LOOK_TOP_DECK focused tests

## 三、範圍內

新增 typed model：

- `PendingChoice`
- `ChoiceKind`
- `ChoiceConstraints`
- `ChoiceCandidate`
- `ChoiceContinuation`
- `PendingChoicePort`
- current-table adapter

遷移：

- LOOK_TOP_DECK create/read/resolve。
- legacy DTO projection。

可以沿用現有 table，不要求本批 DB migration；若現有欄位無法無損保存 typed continuation，先以 versioned JSON context adapter 封裝並補 schema validation。

## 四、非目標

- 不一次遷移所有 decision/interaction type。
- 不刪除 legacy DTO。
- 不改 frontend。
- 不將 Java class name 序列化成 continuation type。
- 不保存 lambda/callback。
- 不在同批搬 `TRIGGER_EFFECT_CONFIRM` 或 attack follow-up。

## 五、資料模型約束

`PendingChoice` 至少保證：

- stable choice ID。
- match/owner/source command。
- kind 與 schema version。
- min/max/ordered/placement/confirmation constraints。
- typed candidates。
- created state version（BE-005 前可 nullable）。
- continuation resume point。
- active/resolved state。

Continuation 必須可在 process restart 後解析，不依賴記憶體物件。

## 六、執行步驟

1. 記錄 LOOK_TOP_DECK 現有 DB row、DTO 與 resolve payload。
2. 補 round-trip characterization test。
3. 建 typed model 與 mapper。
4. 讓 create/read/resolve 透過 `PendingChoicePort`。
5. 保持 `pendingInteractions` 或既有 DTO payload 完全相容。
6. 加 invalid/stale/duplicate resolve tests。
7. 重新啟動式測試：create -> clear application state -> reload -> resolve。

## 七、驗收

- LOOK_TOP_DECK 不再在 resolution service 手動解析 raw context map。
- typed constraints 驗證 placement 與 owner。
- resolve 後 choice 只能完成一次。
- legacy game-state JSON fixture 無 breaking change。
- service restart 不影響續接。
- 其他 pending types 行為不變。

## 八、驗證

```bash
./mvnw -q -Dtest=<PendingChoiceMapperTest> test
./mvnw -q -Dtest=<LookTopDeckChoiceIntegrationTest> test
./mvnw -q -Dtest=MatchDecisionResolutionServiceTest test
./mvnw -q -DskipTests compile
git diff --check
```

## 九、回滾

- legacy create/read/resolve 路徑保留到 pilot 驗收。
- 若 typed mapper 失敗，可讓 adapter 回到原 context parsing。
- 若新增 migration，必須另附 forward-fix；不可假設 production 可 downgrade schema。

## 十、後續

pilot 穩定後依風險順序另開工作包：

1. LOOK_OPPONENT_HAND / LOOK_HOLOPOWER。
2. REORDER_DECK_BOTTOM。
3. DRAW_REVEAL / SEND_CHEER。
4. CARD_SELECTION。
5. TRIGGER_EFFECT_CONFIRM。
