# BE-004：建立 EffectPrimitive 管線

狀態：BLOCKED_BY_BE-003_BE-005
風險：高
Repository：`hololive-cardgame-backend`
建議 commit：`後端：建立版本化效果原語執行管線`
前置工作：BE-003、BE-005

## 一、目標

以既有 `EffectResolver`、`AtomicAction`、`GameActionExecutor` 為基礎，建立 typed、versioned handler registry。pilot 只選一個低中風險 effect family，並保留 `MatchEffectService` legacy fallback；實際 primitive 需在工作開始時依 coverage 與 choice/version 前置重新確認，不預先綁定同批處理 `DRAW` 與 `MOVE_CARD`。

## 二、必讀

- `doc/系統優化/next/02-卡牌對戰引擎藍圖.md`
- `doc/效果原語清單_Handler對映表_遷移策略.md`
- `game/action/EffectResolver.java`
- `game/action/GameActionExecutor.java`
- `MatchDrawEffectExecutionService`
- `MatchMoveZoneEffectExecutionService`
- card effect schema 與 import/migration 流程

此工作涉及核心規則，實作前必須回報影響、雙軌策略、驗證與回滾。

## 三、範圍內

新增：

- `EffectPlan`
- `EffectStep`
- `EffectPrimitive`
- `EffectPrimitiveHandler<P>`
- `EffectHandlerRegistry`
- `DrawEffectParams`
- `MoveCardEffectParams`
- schema/version validation
- legacy fallback metric/result

可重用 `AtomicAction`，但 handler output 必須 typed，不能以任意 map 作唯一契約。

## 四、非目標

- 不遷移所有官方卡。
- 不刪 `MatchEffectService`。
- 不在 runtime 重新解析日文自然語言。
- 不同批修改 DAMAGE、DOWN、Gift timing。
- 不建立每張卡一個 handler。
- 不讓 primitive handler直接建立 frontend DTO。

## 五、目標流程

```text
EffectPlan(version)
  -> validate
  -> registry.resolve(step.type)
  -> handler.plan(context, typed params)
  -> atomic mutations / domain events / PendingChoice
  -> execute via mutation port
  -> EffectExecutionResult
```

結果至少包含：

- executed primitive。
- produced events。
- created choice（可空）。
- unsupported/legacy reason。
- deterministic summary fields。

## 六、雙軌規則

1. typed plan 可識別且 schema valid：走 primitive。
2. 未遷移或不支援：明確走 legacy adapter。
3. typed handler failure 不得靜默 fallback，避免同一卡因 bug 改走另一條語意。
4. 每次 legacy fallback 記 card/effect/version metric。
5. 可用 feature flag 對指定 effect family/card set 開啟，但 flag 預設不改現有 production 行為。

## 七、執行步驟

1. 以現有 DRAW/MOVE_ZONE tests 建 golden behavior。
2. 定義 v1 schema 與 typed params。
3. 建 registry，拒絕 duplicate handler。
4. adapter 將既有 resolver output 對接新 result，或讓現有 service 委派新 handler。
5. 建 dual-run comparison test：同 fixture 的 state diff/event summary 相同。
6. 增加 import-time validation command/test。
7. 只遷移少量 fixture/測試卡，不批次更新 production card data。

## 八、驗收

- DRAW/MOVE_CARD 可完全不依賴效果文字 regex 執行。
- invalid params 在執行 mutation 前失敗。
- duplicate primitive registration 啟動/測試時失敗。
- legacy path 有可查詢原因，不是空 list。
- 同 seed/context 的結果穩定。
- typed path 與 legacy golden state diff 一致。
- 未遷移卡全部仍可走既有路徑。

## 九、驗證

```bash
./mvnw -q -Dtest=<EffectHandlerRegistryTest> test
./mvnw -q -Dtest=<DrawEffectPrimitiveHandlerTest> test
./mvnw -q -Dtest=<MoveCardEffectPrimitiveHandlerTest> test
./mvnw -q -Dtest=<DualRunEffectComparisonIntegrationTest> test
./mvnw -q -DskipTests compile
git diff --check
```

另跑與 pilot 卡相關的 official smoke bucket。

## 十、回滾

- feature flag 關閉 typed path。
- legacy adapter 維持可用。
- schema/data migration 若存在，採 additive，舊欄位不刪。
- 不在本批移除 resolver/service，所以可快速切回。
