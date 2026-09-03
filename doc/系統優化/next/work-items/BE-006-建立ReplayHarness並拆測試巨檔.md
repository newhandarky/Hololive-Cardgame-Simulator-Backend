# BE-006：建立 ReplayHarness 並拆測試巨檔

狀態：BLOCKED_BY_BE-004_BE-005
風險：中高
Repository：`hololive-cardgame-backend`
建議 commit：`測試：建立對戰回放基座並拆分回合測試`
前置工作：BE-004、BE-005

## 一、目標

建立 deterministic scenario/replay test harness，並從 `MatchActionServiceIntegrationTest` 搬出一個完整 action family。pilot 建議選 `turn lifecycle`，不要同批搬 attack/effect。

## 二、必讀

- `doc/系統優化/next/04-測試策略與品質閘門.md`
- `MatchActionServiceIntegrationTest`
- `MatchActionFlowIntegrationTestSupport`
- `MatchIntegrationTestSupport`
- turn lifecycle focused tests
- command receipt/stateVersion/rng version 實作

## 三、範圍內

新增：

- `MatchScenario`
- `MatchScenarioBuilder`
- `MatchCommandScript`
- `ReplayHarness`
- canonical `MatchStateHasher`
- `MatchInvariantAssertions`
- `turn/` focused integration test class

搬移：

- turn start/draw/cheer/phase/end-turn 的一組現有 tests。

## 四、非目標

- 不一次拆完整 32,000 行檔案。
- 不改 production rule 來配合 test。
- 不用 snapshot 大字串掩蓋語意 assert。
- 不把 fixture SQL 暴露成一般 test action API。
- 不宣稱 production 可只靠 event log 重建，除非實際已做到。

## 五、ReplayHarness 契約

輸入：

- initial fixture/snapshot。
- ruleset/cardData/rng versions。
- accepted command script。

輸出：

- final viewer-neutral domain state。
- canonical state hash。
- emitted event sequence。
- 每個 stateVersion 的 checkpoint。

失敗輸出第一個不同：

- command index。
- stateVersion。
- event type。
- state path/value。

## 六、State hash

canonicalization：

- map key 排序。
- set/zone 依規則指定 order。
- 排除 createdAt/serverTime 等非 domain 欄位。
- 保留 card instance order、RNG cursor、pending choice、turn usage。
- hash algorithm/version 明確保存。

## 七、執行步驟

1. 列出 turn lifecycle 測試清單與共用 fixture。
2. 先抽 support classes，原測試仍通過。
3. 建 scenario builder，透過正式 command gateway 執行。
4. 建 state hasher/invariant assertions。
5. 複製一個 test 到新類別確認等價，再從舊檔移除。
6. 分批搬完整 turn family。
7. 新增 duplicate/stale/replay deterministic scenarios。
8. 確認 test discovery 與 CI 可單獨跑 `turn` group。

## 八、驗收

- 至少一條完整回合 script 可重播並得到相同 hash。
- 換 seed 時只在預期 random path 產生差異。
- duplicate command replay 不改 hash。
- 新 turn test class 可獨立執行。
- 原巨檔對應 tests 已移除，沒有重複執行。
- production source 無行為 diff。
- fixture/support 類別不形成新的千行 God Object；超過約 500 行需說明責任。

## 九、驗證

```bash
./mvnw -q -Dtest=<ReplayHarnessTest> test
./mvnw -q -Dtest=<TurnLifecycleIntegrationTest> test
./mvnw -q -Dtest=MatchActionServiceIntegrationTest test
./mvnw -q -DskipTests compile
git diff --check
```

完整巨檔測試可能耗時；若本機資源不足，至少跑搬移前後 test list 與 focused group，並在回報說明。

## 十、回滾

- 純測試搬移可把 test methods 移回原類別。
- ReplayHarness 尚未被 production 使用，可獨立移除。
- 不應有 DB migration 或 API 變更。
