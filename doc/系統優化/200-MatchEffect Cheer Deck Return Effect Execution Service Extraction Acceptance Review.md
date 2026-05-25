# MatchEffect Cheer Deck Return Effect Execution Service Extraction Acceptance Review

日期：2026-05-25
狀態：完成

## 背景

本批延續 production god class 拆分主線，針對 `RETURN_CHEER_TO_DECK_BOTTOM` 抽出專用 execution service。這個 effect 屬於低中風險 zone movement，責任集中在 Cheer candidate 查詢、移動到 `CHEER_DECK` 底部、附屬 Cheer row 清理與 summary payload。

## 完成內容

- 新增 `MatchCheerDeckReturnEffectExecutionService`。
- `MatchEffectService` 的 support / gift dispatch 對 `RETURN_CHEER_TO_DECK_BOTTOM` 改為委派新 service。
- `MatchBloomEffectDispatcher` 與 `MatchCollabEffectDispatcher` 改為直接委派新 service。
- 補上 `MatchCheerDeckReturnEffectExecutionServiceTest`，鎖住 Archive 與 Stage attached Cheer 兩條路徑。
- `bloomShouldTriggerReturnCheerToDeckBottomEffectFromPassiveText` 補上既有 `TRIGGER_EFFECT_CONFIRM` pending resolution，對齊同檔其他 Bloom passive effect 測試流程。

## 責任邊界

`MatchCheerDeckReturnEffectExecutionService` 負責：

- raw text 中的 Cheer 顏色條件解析。
- `value` / `amount` 與日文文字的 return count 解析。
- Archive Cheer candidate 查詢。
- Stage attached Cheer candidate 查詢。
- `ARCHIVE` / `STAGE` 到 `CHEER_DECK` 的 zone 更新。
- `CHEER_DECK` 底部 `order_index` 計算。
- 回牌庫底時設定 `is_face_down = TRUE`。
- Stage attached Cheer 成功移動後刪除 `match_holomem_cheers` row。
- summary payload：`sourceZone`、`returnRequested`、`returnApplied`、`colorFilter`、`returnedCardInstanceIds`、`returnedCardIds`。

本批不處理：

- `DOWN_NO_LIFE` / `DOWN_EXTRA_LIFE`
- `DAMAGE` / `HEAL` / `MOVE_ZONE`
- 公開 API、DB migration、seed data
- 互動式選牌流程

## 行數變化

- `MatchEffectService.java`：`9,489` 行 -> `9,343` 行，淨減 `146` 行。
- 新增 `MatchCheerDeckReturnEffectExecutionService.java`：`262` 行。
- 新增 `MatchCheerDeckReturnEffectExecutionServiceTest.java`：`217` 行。

## 驗證結果

已執行：

```bash
./mvnw -q -Dtest=MatchCheerDeckReturnEffectExecutionServiceTest test
./mvnw -q -DskipTests compile
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#collabHsd13015ShouldReturnStageCheerThenAddCheer test
./mvnw -q -Dtest=MatchActionServiceIntegrationTest#bloomShouldTriggerReturnCheerToDeckBottomEffectFromPassiveText test
```

整合測試第一次在 sandbox 內因 Docker / PostgreSQL socket 權限失敗，已依授權提高權限重跑並通過。

## 下一步建議

下一批可評估 `DOWN_NO_LIFE` / `DOWN_EXTRA_LIFE`。這兩個效果收益較高，但牽涉 down event、life 與 gift follow-up，建議先補 focused tests，再決定是否同批抽成一個 Down execution service，或拆成更小批次處理。
