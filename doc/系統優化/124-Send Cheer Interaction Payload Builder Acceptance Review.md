# Send Cheer Interaction Payload Builder Acceptance Review

日期：2026-04-30
狀態：已完成
範圍：MatchAction send cheer interaction confirmed / TURN_CHEER action payload 組裝抽出

---

## 一、目標

本步目標是收斂 MatchAction 內 send cheer interaction resolve 後的 action payload 組裝。

本步只搬移 payload 欄位組裝，不改：

- send cheer action execution
- phase resolution
- main-step Gift preview
- Gift pending decision 建立
- action append 順序

---

## 二、完成內容

- 新增 `SendCheerInteractionPayloadBuilder`
- 抽出 `SEND_CHEER` interaction confirmed payload
- 抽出 `TURN_CHEER` action payload
- MatchAction 改為委派 builder 組裝上述 payload
- 新增 `SendCheerInteractionPayloadBuilderTest`

---

## 三、Allow / Block 清單

### Allow

- 抽出 send cheer interaction confirmed payload 組裝。
- 抽出 TURN_CHEER action payload 組裝。
- 保留 `sourceCardInstanceId` / `sourceCardId` / `targetHolomemCardInstanceId` 欄位。

### Block

- 不改 `gameActionExecutor.execute(...)`。
- 不改 `resolvePhaseAfterSendCheer(...)`。
- 不改 main-step Gift preview。
- 不改 Gift followup pending decision append。
- 不改 action type。

---

## 四、驗證重點

`SendCheerInteractionPayloadBuilderTest` 覆蓋：

- `SEND_CHEER` interaction confirmed payload 欄位。
- `TURN_CHEER` action payload 欄位。

本步亦需通過：

- `SendCheerInteractionPayloadBuilderTest`
- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 五、剩餘缺口

無 blocker。

後續可做：

- 評估 main-step Gift preview / pending append 是否可獨立成 followup helper，但需注意 Gift trigger side effect。
- 盤點 attach support branch payload 是否適合納入 support / oshi payload builder。
- full `MatchActionServiceIntegrationTest` 仍有既有廣泛失敗，需獨立穩定化。

---

## 六、結論

Send cheer interaction payload builder cleanup 通過 acceptance review。

下一步建議先做 code review / commit checkpoint；commit 後評估 main-step Gift followup helper 或 attach support payload builder 的下一個最小拆分。
