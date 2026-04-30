# MatchAction Pending Decision Reader Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本步回到 MatchAction lifecycle / pending interaction cleanup 路線，抽出 `MatchActionService` 內 pending decision gate reader。

本步只處理：

- `hasBlockingPendingDecision(matchId, userId)` 的 SQL reader
- `hasAnyPendingDecision(matchId)` 的 SQL reader
- `hasAnyPendingDecision(matchId, userId)` 的 SQL reader

不包含：

- pending decision creation
- pending decision resolve / mark resolved
- pending decision schema
- action flow gate 規則
- legacy bridge 內 duplicated reader

## 二、完成內容

- 新增 `PendingDecisionReader`。
- `MatchActionService` 建立 reader instance。
- `MatchActionService` 既有 private methods 保留為 adapter，內部委派 reader。
- 新增 `PendingDecisionReaderTest` 鎖定 SQL filter 與 null / zero count 行為。

## 三、Allow / Block 對照

### Allow

- 把 pending decision count SQL 從 `MatchActionService` 移到 package-private reader。
- 保留 `MatchActionService` private method surface，避免同步改大量呼叫點。
- 用 focused unit test 鎖 reader SQL 與參數。

### Block

- 不改 pending gate 條件。
- 不改 `PENDING` status 語意。
- 不改 pending decision create / resolve SQL。
- 不改 legacy bridge reader。
- 不改任何 player action 的 pending 阻擋規則。

## 四、測試結果

已通過：

- `./mvnw -q -Dtest=PendingDecisionReaderTest test`
- `./mvnw -q -DskipTests compile`

## 五、大檔尺寸變化

- `MatchActionService.java`：`6,134` -> `6,099` 行，減少 `35` 行。
- 新增 `PendingDecisionReader.java`：`63` 行。
- 新增 `PendingDecisionReaderTest.java`：`80` 行。

## 六、剩餘缺口

目前沒有本步 blocker。

保留缺口：

- `BloomLegacyResolutionBridge` / `CollabLegacyResolutionBridge` / `PlayCardLegacyResolutionBridge` / `AttachCheerLegacyResolutionBridge` 仍有類似 pending reader，若要共用需另開 planning，避免一次改多條 legacy bridge。
- `MatchTurnLifecycleService` 內 pending reader 尚未納入本步。
- 完整 `MatchActionServiceIntegrationTest` 仍有既有廣域不穩定，不作為本步 blocker。

## 七、結論

MatchAction pending decision reader cleanup 可視為完成。

本步只下沉 pending gate count SQL，保留 MatchAction 既有 private method 呼叫面與行為語意。

## 八、下一步

進入 code review / commit checkpoint。

commit 後建議評估是否規劃跨 legacy bridge 的 pending reader 共用；若收益不足，轉向 MatchEffectService reader / payload builder 型 cleanup。
