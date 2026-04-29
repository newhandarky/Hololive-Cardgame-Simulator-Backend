# Followup Source Card Payload Helper Cleanup Planning

更新日期：2026-04-29
定位：`FollowupTriggerConfirmPendingDecisionWriter` 共用化後的下一個低風險 cleanup 評估

---

## 一、背景

`79-FollowupTriggerConfirmPendingDecisionWriter Acceptance Review.md` 建議後續可評估三個 effect resolution service 的 source card payload helper。

目前共用 writer 已處理 pending decision persistence，但 pending context 內的 `cards` payload 仍由各 use case 自行組裝。這個邊界是合理的，因為不同 use case 的 fallback zone 與卡片來源不同。

本文件只盤點與規劃，不直接修改 production code。

---

## 二、目前盤點

### BLOOM

服務：

- `BloomEffectResolutionService`

目前 source card payload：

- 只放 bloom card 本身。
- 呼叫 `FollowupCardCandidateLoader.loadOwnedCardCandidateForDecision(...)`。
- fallback zone 固定為 `STAGE`。
- 已補 focused baseline：
  - `BloomEffectResolutionServiceTest.resolveAfterBloomShouldCreateConfirmPendingDecisionWhenBloomEffectExists`
  - 鎖住 `BLOOM` / `BLOOM_EFFECT` insert args
  - 鎖住 fallback `cards[0].zone = STAGE`

### PLAY_CARD

服務：

- `PlayCardEffectResolutionService`

目前 source card payload：

- 只放進場卡本身。
- 呼叫 `FollowupCardCandidateLoader.loadOwnedCardCandidateForDecision(...)`。
- fallback zone 使用 `resolutionResult.targetZone()`。
- Gift trigger payload 與 selection context 仍由 PLAY_CARD service 組裝。

### COLLAB

服務：

- `CollabEffectResolutionService`

目前 source card payload：

- `buildGiftTriggerInteractionCards(...)` 可能放入多張卡：
  - collab source card
  - Gift holder card
- source card 初始 fallback zone 多用 `STAGE`。
- collab-only fallback path 可用 `COLLAB`。
- Gift holder fallback zone 使用 `giftHolderZone`，沒有值時才 fallback `STAGE`。
- 這條路徑比 BLOOM / PLAY_CARD 複雜，不應用單一固定 fallback zone 抽象。

---

## 三、風險判斷

### 低風險可做

- 先補 PLAY_CARD source card payload baseline。
- 再補 COLLAB source / Gift holder cards baseline。
- 若三條 baseline 都穩定，再抽一個小型 helper 包裝 `FollowupCardCandidateLoader` 呼叫。

### 不應直接做

- 不把 trigger payload、selection context、message builder 一起抽成共用 framework。
- 不改 `FollowupTriggerConfirmPendingDecisionWriter` input shape。
- 不改 `cards` payload JSON 欄位名稱。
- 不把 fallback zone 統一成同一個常數。
- 不把 Gift holder card selection 邏輯搬進 BLOOM / PLAY_CARD 共用 helper。

---

## 四、建議切法

### Step FSCP-1：PLAY_CARD baseline

目標：

- 補 / 強化 `PlayCardEffectResolutionServiceTest.resolveShouldCreateGiftConfirmPendingDecisionWhenGiftTriggersExist`。
- 鎖住 pending context `cards` fallback payload：
  - `cardInstanceId`
  - `cardId`
  - `zone = targetZone`
- 保留 `GIFT` / `GIFT_TRIGGER` insert args。

不做：

- 不改 production code。

### Step FSCP-2：COLLAB baseline

目標：

- 補 / 強化 COLLAB pending context `cards` payload：
  - collab source card fallback
  - Gift holder card fallback
  - duplicate holder 不重複加入
- 鎖住 fallback zone：
  - source card 視入口使用 `STAGE` 或 `COLLAB`
  - Gift holder 使用 holder zone

不做：

- 不改 production code。

### Step FSCP-3：小 helper port 評估

前提：

- FSCP-1 / FSCP-2 baseline 通過。

可能做法：

- 新增 package-private helper，例如 `FollowupSourceCardPayloadBuilder`。
- helper 只包裝 loader 與 fallback zone，不碰 trigger payload / selection context。
- BLOOM / PLAY_CARD / COLLAB 逐條改用 helper，每條都保留既有 focused tests。

---

## 五、建議驗證

Focused：

- `./mvnw -q -Dtest=BloomEffectResolutionServiceTest,PlayCardEffectResolutionServiceTest,CollabEffectResolutionServiceTest,FollowupTriggerConfirmPendingDecisionWriterTest test`

Static：

- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 六、下一步

下一步建議先做 FSCP-1：補 PLAY_CARD source card payload baseline，不直接改 production code。
