# MatchAction Gift Trigger Pending Facade Cleanup Planning

日期：2026-04-30
狀態：規劃完成

## 一、背景

Gift trigger pending 流程已完成多輪拆分：

- `GiftTriggeredEffectConfirmMessageBuilder` 已承接一般 Gift confirm message。
- `GiftTriggeredEffectConfirmPendingInputBuilder` 已承接 pending input shape。
- `FollowupTriggerConfirmPendingDecisionWriter` 已承接 pending decision 寫入。
- `GiftTriggerInteractionCardsBuilder` 已承接 interaction cards payload。
- `GiftPendingDecisionCreator` 已承接一般 Gift pending decision 建立。
- `PlayCardEffectResolutionService` 已直接使用 `GiftPendingDecisionCreator.createWithCards(...)`。
- `MatchActionService` 的 main step Gift 與 baton touch Gift 已收斂到 `GiftPendingDecisionCreator.createWithGiftTriggerInteractionCards(...)`。

本輪目標不是再改 Gift 規則，而是盤點 legacy facade 是否還有殘留責任可縮薄，避免後續 use case cleanup 重複處理 pending payload、source cards、message 與 writer 接線。

## 二、現況盤點

### 已收斂的入口

`GiftPendingDecisionCreator` 目前提供兩個低階入口：

1. `createWithGiftTriggerInteractionCards(...)`
   - 內部建立 Gift trigger interaction cards。
   - 適合 main step Gift、baton touch Gift 這類只需要標準 source cards 的 flow。
2. `createWithCards(...)`
   - caller 已先準備 cards payload。
   - 適合 PLAY_CARD 這類需要指定入場卡 payload 的 flow。

`MatchActionService` 目前只保留薄 adapter：

- `createGiftTriggerDecisionWithoutSourceCard(...)`
- `createBatonTouchGiftTriggerDecision(...)`

兩者都委派 `GiftPendingDecisionCreator.createWithGiftTriggerInteractionCards(...)`。

### 仍需分開看待的入口

`AttackArtPendingDecisionCreator` 仍自行組 pending input：

- attack art post-trigger pending 必須保留 attack outer confirm message 與 Down Event preview。
- defender Gift pending 目前使用一般 Gift pending input，但會轉為 `AttackPendingDecision`。

這兩個 flow 不應在同一步硬套進 `GiftPendingDecisionCreator`，除非先有 attack focused regression 保護。

## 三、允許範圍

- 盤點剩餘 Gift trigger pending call site。
- 補 focused regression，鎖住：
  - main step Gift pending decision context
  - baton touch Gift source card context
  - PLAY_CARD Gift source card payload
  - attack defender Gift pending conversion
- 若 production slice 足夠小，可以只把 `MatchActionService` 的薄 adapter 命名或位置再收斂。
- 保留既有 pending decision writer 與 input builder 的資料 shape。

## 四、禁止範圍

- 不改 pending decision schema。
- 不改 `giftTriggers` / `giftCount` / `context` payload shape。
- 不改 Gift trigger timing。
- 不改 turn once 判定。
- 不改 attack post-trigger outer message。
- 不改 Down Event preview 與 attack pending conversion。
- 不把 attack post-trigger 與一般 Gift pending 在沒有測試保護下合併。

## 五、建議分批

### MGTPF-1：Call-site regression baseline

先補測試，不改 production：

- `MatchActionServiceTest`
  - main step Gift pending 沒有 source card 時仍能建立標準 Gift pending。
  - baton touch Gift pending 保留 source card context。
- `PlayCardEffectResolutionServiceTest`
  - PLAY_CARD Gift pending 保留 played card payload。
- `AttackArtPendingDecisionCreatorTest`
  - defender Gift pending 仍轉成 `AttackPendingDecision`。

### MGTPF-2：MatchAction thin adapter cleanup

若 MGTPF-1 無 blocker，再評估：

- 是否保留 `createGiftTriggerDecisionWithoutSourceCard(...)` 作為語意 adapter。
- 是否將 baton touch adapter 改名，使其更明確只包裝 `GiftPendingDecisionCreator`。
- 是否移除 `MatchActionService` 已不需要直接持有的 pending input builder 欄位。

### MGTPF-3：Acceptance review

最後補 acceptance review：

- 對照 allow/block 清單。
- 確認沒有誤改 pending payload。
- 確認 attack post-trigger 與 defender Gift 未被一般 Gift cleanup 破壞。

## 六、驗證策略

MGTPF-1 預期跑：

```bash
./mvnw -q -Dtest=MatchActionServiceTest,PlayCardEffectResolutionServiceTest,AttackArtPendingDecisionCreatorTest,GiftPendingDecisionCreatorTest test
```

MGTPF-2 若有 production 改動，至少加跑：

```bash
./mvnw -q -DskipTests compile
```

並固定執行：

```bash
git diff --check
```

## 七、下一步

進入 code review / commit checkpoint。

commit 後建議執行 MGTPF-1，先補 Gift trigger pending call-site focused regression baseline。
