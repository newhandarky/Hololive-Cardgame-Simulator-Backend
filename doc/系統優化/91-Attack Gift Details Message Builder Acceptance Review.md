# Attack Gift Details Message Builder Acceptance Review

更新日期：2026-04-29
狀態：通過

---

## 一、驗收範圍

本文件驗收 attack post-trigger Gift details message 接共用 builder 的小步收斂。

範圍包含：

- `AttackPostTriggerConfirmMessageBuilder`
- `AttackPostTriggerConfirmMessageBuilderTest`
- `GiftTriggeredEffectDetailsMessageBuilder`

不包含：

- attack outer confirm message 文案調整
- Down Event message 文案調整
- attack post-trigger pending payload shape
- trigger sections payload
- pending decision writer
- `match_pending_decisions` SQL 欄位
- ATTACK 主流程規則

---

## 二、完成條件檢查

### attack Gift details 接線

狀態：完成

`AttackPostTriggerConfirmMessageBuilder.buildGiftTriggeredEffectDetails(...)` 已改為委派 `GiftTriggeredEffectDetailsMessageBuilder`。

保留：

- package-private wrapper
- attack builder 對外測試入口
- `MatchActionService.buildGiftTriggeredEffectDetails(...)` adapter 呼叫面
- Gift details 原格式

### Down Event message 保留

狀態：完成

`AttackPostTriggerConfirmMessageBuilder` 仍自行組裝 Down Event 區塊。

保留：

- `[Down Event]`
- `DOWN_EVENT`
- downed card id
- requested life loss
- raw text append
- Down Event 在 Gift 前面

### empty Gift details 防護

狀態：完成

共用 builder 對 null / empty trigger 會回傳空字串。

attack builder 已補防護：只有 Gift details 實際有文字時才加入 `[Gift]` 區塊。

---

## 三、Allow / Block 清單

### Allow

- attack post-trigger Gift details 改用 `GiftTriggeredEffectDetailsMessageBuilder`。
- 移除 attack builder 內重複的 Gift details formatting / normalization helper。
- 讓 PLAY_CARD / COLLAB / attack post-trigger 三個 Gift details message 入口共用同一個 builder。
- 對空 Gift details 不產生空 `[Gift]` 區塊。

### Block

- 不改 attack outer confirm message。
- 不改 Down Event message。
- 不改 Down Event 與 Gift 的排序。
- 不改 pending payload shape。
- 不改 trigger sections。
- 不改 follow-up action writer。
- 不改 SQL。
- 不擴大到 ATTACK 主流程規則。

---

## 四、測試與驗證

已執行並通過：

- `./mvnw -q -Dtest=AttackPostTriggerConfirmMessageBuilderTest,GiftTriggeredEffectDetailsMessageBuilderTest test`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

測試涵蓋：

- attack post-trigger default message
- Down Event before Gift
- Gift details formatting
- invalid requestedEffects fallback
- empty Gift details 不產生空 `[Gift]` 區塊
- 共用 Gift details builder focused behavior

---

## 五、剩餘缺口

無 blocker。

可後續補強但不阻擋本輪收斂：

- 補 API / integration smoke 驗證 attack post-trigger pending decision message 文本。
- 檢查 `MatchActionService.buildGiftTriggeredEffectConfirmMessage(...)` 仍留在 legacy facade 的用途，評估是否也能收斂到 use-case service 或移除。
- 重新評估目前 full `MatchActionServiceIntegrationTest` 的既有失敗清單，規劃測試穩定化。

---

## 六、結論

Attack post-trigger Gift details message builder 接線通過 acceptance review。

本輪已完成：

1. attack Gift details 接共用 builder
2. 保留 Down Event message
3. 保留 attack outer confirm message
4. focused tests
5. compile 驗證
6. acceptance review

下一步建議進入 Gift follow-up message / legacy facade cleanup 的下一個小型規劃點，優先評估 `MatchActionService.buildGiftTriggeredEffectConfirmMessage(...)` 是否仍有必要保留，或改補 MatchActionServiceIntegrationTest 穩定化規劃。
