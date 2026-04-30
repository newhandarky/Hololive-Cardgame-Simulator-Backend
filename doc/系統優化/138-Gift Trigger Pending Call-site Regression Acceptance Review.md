# Gift Trigger Pending Call-site Regression Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本文件驗收 MGTPF-1：Gift trigger pending call-site regression baseline。

本步只補 focused regression test，不改 production code。

## 二、完成內容

新增 `GiftPendingDecisionCreatorTest.createWithGiftTriggerInteractionCardsShouldKeepMainStepGiftWithoutSourceCard`。

此測試鎖住 main step Gift pending 的關鍵 shape：

- `source_action_type = GIFT`
- `source_card_instance_id = null`
- `source_card_id = null`
- `effect_type = GIFT_TRIGGER`
- context 保留 `sourceActionType = GIFT`
- context cards 仍包含 Gift holder card
- context 保留 `triggerType`
- context 保留 `giftHolderCardInstanceId`
- context 保留 `giftCount`

## 三、既有測試保護

本批也重新跑過既有 call-site regression：

- `GiftPendingDecisionCreatorTest`
  - PLAY_CARD / explicit cards path
  - baton touch source card context
  - empty Gift effects returns null
  - main step Gift without source card
- `PlayCardEffectResolutionServiceTest`
  - PLAY_CARD Gift pending played card payload
- `AttackArtPendingDecisionCreatorTest`
  - attack art post-trigger pending outer message
  - defender Gift pending conversion to `AttackPendingDecision`

## 四、Allow / Block 對照

### Allow

- 補 regression test 鎖住 pending payload shape。
- 使用既有 `GiftPendingDecisionCreator` 與 writer 行為驗證。
- 用 focused unit test 取代高成本完整 integration suite。

### Block

- 未改 pending decision schema。
- 未改 `giftTriggers` / `giftCount` / `context` payload shape。
- 未改 Gift trigger timing。
- 未改 turn once 判定。
- 未改 attack post-trigger outer message。
- 未改 Down Event preview。
- 未把 attack defender Gift 併入一般 Gift pending creator。

## 五、測試結果

已通過：

```bash
./mvnw -q -Dtest=GiftPendingDecisionCreatorTest,PlayCardEffectResolutionServiceTest,AttackArtPendingDecisionCreatorTest test
```

已通過：

```bash
git diff --check
```

## 六、結論

MGTPF-1 完成，沒有 blocker。

目前 main step Gift、baton touch Gift、PLAY_CARD Gift 與 attack defender Gift 的 pending call-site baseline 已有 focused regression 保護。

下一步建議進入 MGTPF-2，評估 `MatchActionService` 是否仍需要直接持有 `GiftTriggeredEffectConfirmPendingInputBuilder`，以及 thin adapter 命名是否可再收斂。
