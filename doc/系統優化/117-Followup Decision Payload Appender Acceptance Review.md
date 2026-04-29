# Followup Decision Payload Appender Acceptance Review

日期：2026-04-29
狀態：已完成
範圍：MatchAction followup decision payload 欄位寫回 helper 抽出

---

## 一、目標

本步目標是將 MatchAction 內重複使用的 followup decision payload 寫回邏輯抽成獨立 helper。

此 helper 負責將 `FollowupInteractionDecision` 轉成 action payload 的相容欄位：

- `pendingInteractionDecisionId`
- `pendingInteractionDecisionType`
- `pendingLookTopDeckDecisionId`

本步不改任何 pending decision 建立流程，也不改前端相容 payload key。

---

## 二、完成內容

- 新增 `FollowupDecisionPayloadAppender`
- MatchAction 改為委派 appender 寫入 followup decision payload
- 移除 MatchAction 內部 `putFollowupDecisionPayload(...)`
- 新增 `FollowupDecisionPayloadAppenderTest`

---

## 三、Allow / Block 清單

### Allow

- 抽出 payload 欄位寫回 helper。
- 保留空值保護。
- 保留 LOOK_TOP_DECK 的相容欄位。

### Block

- 不改 action payload key。
- 不改 decision type。
- 不改 pending decision schema。
- 不改 support / oshi / baton touch / collab followup decision 建立順序。
- 不改對手 followup payload helper。

---

## 四、驗證重點

`FollowupDecisionPayloadAppenderTest` 覆蓋：

- 一般 decision 寫入 common pending interaction 欄位。
- LOOK_TOP_DECK decision 額外保留 `pendingLookTopDeckDecisionId`。
- null payload / null decision / null decision id 不寫入。

本步亦需通過：

- `EffectFollowupDecisionResolverTest`
- `EffectPostTriggerPendingServiceTest`
- `FollowupInteractionContextResolverTest`
- `FollowupInteractionPendingDecisionWriterTest`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 `putOpponentFollowupDecisionPayload(...)` 是否也能以相同模式抽出。
- 盤點 support / oshi skill payload assembly 是否適合進一步收斂。
- full `MatchActionServiceIntegrationTest` 仍有既有廣泛失敗，需獨立穩定化。

---

## 六、結論

Followup decision payload appender cleanup 通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後評估 opponent followup payload helper 或 support / oshi skill payload assembly 的下一個最小拆分。
