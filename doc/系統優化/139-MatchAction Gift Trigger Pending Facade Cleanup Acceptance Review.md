# MatchAction Gift Trigger Pending Facade Cleanup Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本文件驗收 MGTPF-2：MatchAction Gift trigger pending thin facade cleanup。

本步只移除 `MatchActionService` 不必要的 instance field，未改 Gift pending creation 行為。

## 二、完成內容

`MatchActionService` 原本將 `GiftTriggeredEffectConfirmPendingInputBuilder` 存為欄位，但實際用途只在 constructor 內接給：

- `GiftPendingDecisionCreator`
- `AttackArtPendingDecisionCreator`

本步改為 constructor local：

- 移除 `private final GiftTriggeredEffectConfirmPendingInputBuilder giftTriggeredEffectConfirmPendingInputBuilder`
- 在 constructor 內建立 local builder
- 保持同一個 builder instance 同時傳給 `GiftPendingDecisionCreator` 與 `AttackArtPendingDecisionCreator`

## 三、Allow / Block 對照

### Allow

- 移除不再代表 `MatchActionService` runtime responsibility 的欄位。
- 保留 creator 之間既有接線與 builder instance。
- 使用 focused tests 與 compile 驗證 constructor 接線。

### Block

- 未改 pending decision schema。
- 未改 pending context JSON shape。
- 未改 Gift trigger timing。
- 未改 main step Gift / baton touch Gift adapter 行為。
- 未改 PLAY_CARD Gift flow。
- 未改 attack post-trigger outer message。
- 未改 Down Event preview 與 attack pending conversion。

## 四、測試結果

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

## 五、結論

MGTPF-2 完成，沒有 blocker。

`MatchActionService` 不再把 Gift pending input builder 暴露為 service 欄位，Gift trigger pending builder ownership 更接近 creator 接線層。

下一步建議進入 MGTPF-3，收束 Gift trigger pending facade cleanup acceptance review；完成後再回到下一個 MatchAction legacy cleanup 小切片。
