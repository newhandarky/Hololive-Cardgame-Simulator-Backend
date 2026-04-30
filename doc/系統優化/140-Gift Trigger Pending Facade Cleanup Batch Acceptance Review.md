# Gift Trigger Pending Facade Cleanup Batch Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本批範圍

本文件收束 `137` 至 `139` 的 Gift trigger pending facade cleanup batch：

- `137-MatchAction Gift Trigger Pending Facade Cleanup Planning.md`
- `138-Gift Trigger Pending Call-site Regression Acceptance Review.md`
- `139-MatchAction Gift Trigger Pending Facade Cleanup Acceptance Review.md`

本批目標是縮小 `MatchActionService` 在 Gift trigger pending creation 上的殘留 ownership，並補足 call-site regression baseline。

## 二、完成條件對照

### MGTPF-1：Call-site regression baseline

已完成。

- 新增 main step Gift without source card regression。
- 重新驗證 PLAY_CARD Gift pending source payload。
- 重新驗證 baton touch Gift source card context。
- 重新驗證 attack post-trigger 與 defender Gift pending conversion。

### MGTPF-2：MatchAction thin adapter cleanup

已完成。

- 移除 `MatchActionService` 不必要的 `GiftTriggeredEffectConfirmPendingInputBuilder` instance field。
- 改為 constructor local builder。
- 保留 `GiftPendingDecisionCreator` 與 `AttackArtPendingDecisionCreator` 既有接線。

### MGTPF-3：Acceptance review

已完成。

- 本文件收束 allow/block 清單與測試缺口。
- 本批無 blocker。

## 三、舊入口 allow / block 清單

### Allow

- `MatchActionService.createGiftTriggerDecisionWithoutSourceCard(...)` 可暫留作語意 adapter。
- `MatchActionService.createBatonTouchGiftTriggerDecision(...)` 可暫留作 baton touch caller adapter。
- `GiftPendingDecisionCreator.createWithGiftTriggerInteractionCards(...)` 繼續承接標準 Gift source cards flow。
- `GiftPendingDecisionCreator.createWithCards(...)` 繼續承接 caller 已指定 cards 的 flow。

### Block

- 不移除 main step Gift adapter，除非先把 advance phase / main step call site 命名與 regression 一起整理。
- 不把 attack art post-trigger pending 改走一般 Gift creator。
- 不改 attack outer confirm message。
- 不改 Down Event preview。
- 不改 `AttackPendingDecision` conversion。
- 不改 pending decision schema 或 context JSON shape。

## 四、測試缺口

本批已補 focused unit baseline，但仍有下列後續測試缺口：

- `MatchActionServiceIntegrationTest` 完整 suite 仍偏大且既有不穩，未納入本批 blocker。
- main step Gift integration smoke 可在下一輪穩定化規劃中補一個較小案例。
- attack defender Gift integration smoke 若未來要合併 creator，必須先補。

## 五、已驗證項目

已通過：

```bash
./mvnw -q -Dtest=GiftPendingDecisionCreatorTest,PlayCardEffectResolutionServiceTest,AttackArtPendingDecisionCreatorTest test
```

已通過：

```bash
./mvnw -q -Dtest=GiftPendingDecisionCreatorTest,AttackArtPendingDecisionCreatorTest test
```

已通過：

```bash
./mvnw -q -DskipTests compile
```

已通過：

```bash
git diff --check
```

## 六、結論

Gift trigger pending facade cleanup batch 可收束。

下一步建議回到 MatchAction legacy cleanup 路線，優先評估 main step Gift / advance phase Gift thin adapter 命名與 integration smoke，而不是繼續擴大到 attack post-trigger creator 合併。
