# Pending Decision Reader Adoption Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本步承接 `167-MatchAction Pending Decision Reader Acceptance Review.md`，把已抽出的 `PendingDecisionReader` 採用到其他仍重複 pending count SQL 的 action validation / lifecycle / followup writer 入口。

本步只處理 pending decision count reader 委派。

不包含：

- pending decision creation SQL
- pending decision resolve / load-for-update SQL
- game state response 的 pending decision 載入
- HardNpc TURN_START pending 建立保護
- pending gate 規則調整

## 二、完成內容

- `EndTurnApplicationService` 採用 `PendingDecisionReader`。
- `MatchTurnLifecycleService` 採用 `PendingDecisionReader`。
- `FollowupInteractionPendingDecisionWriter` 採用 `PendingDecisionReader`。
- `FollowupTriggerConfirmPendingDecisionWriter` 採用 `PendingDecisionReader`。
- `BloomLegacyResolutionBridge` 採用 `PendingDecisionReader`。
- `CollabLegacyResolutionBridge` 採用 `PendingDecisionReader`。
- `PlayCardLegacyResolutionBridge` 採用 `PendingDecisionReader`。
- `AttachCheerLegacyResolutionBridge` 採用 `PendingDecisionReader`。

## 三、Allow / Block 對照

### Allow

- 移除 action validation / lifecycle / followup writer 內重複的 pending count SQL。
- 保留既有 private method surface，讓外層 flow 不需要同步重寫。
- 繼續由既有 `JdbcTemplate` 建立 package-private reader。

### Block

- 不改 pending status 字串語意。
- 不改 pending decision 建立 payload。
- 不改 pending resolve / update 行為。
- 不改任何 action 是否被 pending 阻擋的條件。
- 不把 game state response loader 混入 reader，避免把查詢 DTO 與 gate reader 責任混在一起。

## 四、測試結果

已通過：

- `./mvnw -q -Dtest=PendingDecisionReaderTest test`
- `./mvnw -q -DskipTests compile`

## 五、大檔尺寸變化

- `EndTurnApplicationService.java`：`291` -> `271` 行，減少 `20` 行。
- `MatchTurnLifecycleService.java`：`896` -> `885` 行，減少 `11` 行。
- `BloomLegacyResolutionBridge.java`：`423` -> `403` 行，減少 `20` 行。
- `CollabLegacyResolutionBridge.java`：`289` -> `269` 行，減少 `20` 行。
- `PlayCardLegacyResolutionBridge.java`：`275` -> `255` 行，減少 `20` 行。
- `AttachCheerLegacyResolutionBridge.java`：`268` -> `248` 行，減少 `20` 行。
- `FollowupTriggerConfirmPendingDecisionWriter.java`：`131` -> `120` 行，減少 `11` 行。
- `FollowupInteractionPendingDecisionWriter.java`：`97` -> `86` 行，減少 `11` 行。

## 六、剩餘缺口

目前沒有本步 blocker。

保留缺口：

- `MatchGameStateService` 的 pending SQL 是 response loader，不屬於 gate count reader。
- `MatchActionService.loadPendingDecisionForUpdate(...)` 是 resolve path 的 lock query，不屬於 gate count reader。
- `HardNpcService.ensureTurnStartPendingInteraction(...)` 目前包含 exists + insert guard，若要改用 reader 需另評估 NPC 自動流程語意。

## 七、結論

Pending decision count reader 已從主要 action validation / lifecycle / followup writer 入口收斂成共用 helper。

本步沒有調整規則，只減少重複 SQL 與讓 pending gate 的查詢責任更集中。

## 八、下一步

進入 code review / commit checkpoint。

commit 後建議轉向 MatchEffectService reader / payload builder 型 cleanup，避免在 pending decision 路線上過度擴張到 response loader 或 NPC 自動流程。
