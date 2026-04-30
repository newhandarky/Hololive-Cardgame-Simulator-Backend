# MatchAction Pending Payload Cleanup Batch Acceptance Review

日期：2026-04-30
狀態：已完成
範圍：MatchAction pending / followup / interaction payload cleanup 階段收束

---

## 一、目標

本文件收束 `109` 至 `126` 之後的一批 MatchAction pending / payload cleanup。

本批目標是降低 MatchAction 對 pending decision 建立、followup payload append、support / oshi payload 組裝、interaction confirmed payload 組裝的直接責任。

本批不改：

- 對外 API
- action type
- pending decision schema
- effect summary shape
- support / oshi / send cheer / Gift trigger 產品規則

---

## 二、完成項目

### pending decision / followup

- `GiftPendingDecisionCreator`
- `FollowupTriggerConfirmPendingDecisionCreator`
- `EffectPostTriggerPendingService`
- `DownEventPreviewExtractor`
- `FollowupInteractionPendingDecisionWriter`
- `FollowupInteractionContextResolver`
- `EffectFollowupDecisionResolver`

### payload helper

- `FollowupDecisionPayloadAppender`
- `SupportOshiEffectPayloadBuilder`
- `InteractionConfirmedPayloadBuilder`
- `TriggerEffectConfirmPayloadBuilder`
- `SendCheerInteractionPayloadBuilder`

### MatchAction 私有 helper

- `transitionMatchToMainAndSave(...)`
- `appendMainStepGiftFollowupPayload(...)`

---

## 三、Allow / Block 清單

### Allow

- 移出純 payload builder。
- 移出 pending decision writer / resolver。
- 收斂重複 followup decision payload append。
- 收斂完全相同的 MAIN phase save helper。

### Block

- 不改 pending decision table schema。
- 不改 action payload key。
- 不改 action append 順序。
- 不改 Gift trigger preview side effect。
- 不改 support / oshi skill 規則。
- 不改 send cheer phase resolution。

---

## 四、目前狀態

- `MatchActionService.java` 已由本輪後段的 `6,495` 行附近降至 `6,320` 行。
- 多數 followup / pending / payload 細節已由小型 package-private helper 承接。
- MatchAction 仍是 facade / orchestration 入口，未做 package split 或 controller API 改動。

---

## 五、測試與驗證

本批已分步執行 focused tests，涵蓋：

- `GiftPendingDecisionCreatorTest`
- `FollowupTriggerConfirmPendingDecisionCreatorTest`
- `EffectPostTriggerPendingServiceTest`
- `DownEventPreviewExtractorTest`
- `FollowupInteractionPendingDecisionWriterTest`
- `FollowupInteractionContextResolverTest`
- `EffectFollowupDecisionResolverTest`
- `FollowupDecisionPayloadAppenderTest`
- `SupportOshiEffectPayloadBuilderTest`
- `InteractionConfirmedPayloadBuilderTest`
- `TriggerEffectConfirmPayloadBuilderTest`
- `SendCheerInteractionPayloadBuilderTest`
- `GiftTriggeredEffectDeferredSummaryBuilderTest`

每步亦已執行：

- `./mvnw -q -DskipTests compile`
- `git diff --check`

---

## 六、剩餘缺口

無 blocker。

後續可做：

- 針對剩餘 inline payload，按 use case 另開小批次，例如 PLAY_CARD / mulligan / draw lifecycle。
- 若繼續 MatchAction cleanup，建議先做新一批 planning / acceptance，不要把不同 use case 混進本批。
- full `MatchActionServiceIntegrationTest` 仍有既有廣泛失敗，需獨立穩定化。

---

## 七、結論

本批 MatchAction pending / payload cleanup 通過 batch acceptance review。

下一步建議回到路線圖，評估是否進入下一個 use case cleanup batch；若延續目前方向，優先評估 PLAY_CARD 相關 inline payload / lifecycle helper。
