# Interaction Confirmed Payload Builder Acceptance Review

日期：2026-04-30
狀態：已完成
範圍：MatchAction followup interaction confirmed payload 組裝抽出

---

## 一、目標

本步目標是收斂 MatchAction 內 LOOK / REORDER 類 followup interaction resolve 後的 `INTERACTION_CONFIRMED` payload 組裝。

本步只搬移 action payload 欄位組裝，不改：

- pending decision resolve 流程
- deck card move / reorder 流程
- phase transition 與 save 時機
- action type
- pending decision schema

---

## 二、完成內容

- 新增 `InteractionConfirmedPayloadBuilder`
- 抽出 LOOK_TOP_DECK confirmed payload
- 抽出 LOOK_OPPONENT_HAND / LOOK_HOLOPOWER confirmed payload
- 抽出 REORDER_DECK_BOTTOM confirmed payload
- MatchAction 改為委派 builder 組裝上述 payload
- 新增 `InteractionConfirmedPayloadBuilderTest`

---

## 三、Allow / Block 清單

### Allow

- 抽出 LOOK / REORDER interaction confirmed payload 組裝。
- 保留 `decisionId` / `decisionType` / `sourceActionType` 共用欄位。
- 保留 LOOK_TOP_DECK 的 `lookedCardInstanceId` / `placement` 欄位。
- 保留 LOOK zone 的 `lookedCardCount` 欄位。
- 保留 REORDER_DECK_BOTTOM 的 `orderedCardInstanceIds` 欄位。

### Block

- 不改 trigger effect confirm payload。
- 不改 send cheer interaction confirmed payload。
- 不改 decision resolution ordering。
- 不改 deck card move / reorder 行為。
- 不改 action type。

---

## 四、驗證重點

`InteractionConfirmedPayloadBuilderTest` 覆蓋：

- LOOK_TOP_DECK placement payload。
- LOOK zone count payload。
- REORDER_DECK_BOTTOM ordered cards payload。

本步亦需通過：

- `InteractionConfirmedPayloadBuilderTest`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 trigger effect confirm payload 是否適合另外抽出。
- 評估 send cheer interaction confirmed payload 是否適合另外抽出。
- full `MatchActionServiceIntegrationTest` 仍有既有廣泛失敗，需獨立穩定化。

---

## 六、結論

Interaction confirmed payload builder cleanup 通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後評估 trigger effect confirm payload builder 或 send cheer interaction payload builder 的下一個最小拆分。
