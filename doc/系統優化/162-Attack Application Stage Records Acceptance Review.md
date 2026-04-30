# Attack Application Stage Records Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本步延續 attack adapter glue cleanup，處理 `AttackArtApplicationAdapterFactory` 內純資料型別的 stage result records。

本步只搬移：

- attack application stage result records
- `AttackApplicationRestPayloadStage` payload carrier 型別
- `MatchActionService` 對 rest payload stage class 的型別參照

不包含：

- stage resolver 執行順序
- stage name / stage result key
- attack rule calculation
- action log timing
- finish check timing
- pending decision timing
- payload 欄位 shape

## 二、完成內容

- 新增 `AttackApplicationStages.java`，集中 package-private stage result records。
- 移除 `AttackArtApplicationAdapterFactory` 內的 stage result record 宣告。
- `AttackApplicationRestPayloadStage` 保留 `AttackArtApplicationService.AttackPayloadCarrier` 實作。
- `MatchActionService.attackArt(...)` 改直接使用 package-private `AttackApplicationRestPayloadStage.class`。

## 三、Allow / Block 對照

### Allow

- 將純資料 records 從 adapter factory 搬到同 package top-level 檔案。
- 保留 package-private 可見度，避免擴大 public API。
- 更新既有同 package 型別參照。

### Block

- 不改 `AttackArtApplicationService` stage order。
- 不改 `AttackArtApplicationService` stage constants。
- 不改 resolver 內邏輯。
- 不改 `AttackRestAndPayloadResult` 或 payload carrier contract。
- 不改 `MatchActionService.attackArt(...)` 的流程，只改 stage class reference。

## 四、測試結果

已通過：

- `./mvnw -q -Dtest=AttackArtApplicationServiceTest,AttackArtApplicationAdapterFactoryTest test`

測試覆蓋：

- application service stage order
- adapter factory rest/save -> action log -> finish check order
- rest payload stage 仍可由 stage result 取回並提供 payload carrier

## 五、大檔尺寸變化

- `AttackArtApplicationAdapterFactory.java`：`715` -> `640` 行，減少 `75` 行。
- 新增 `AttackApplicationStages.java`：`79` 行。
- `MatchActionService.java`：`6,180` -> `6,180` 行，行數不變。

## 六、剩餘缺口

目前沒有本步 blocker。

保留缺口：

- `AttackArtApplicationAdapterFactory` 仍有多個 inner stage resolver，後續可繼續拆 resolver provider / resolver class。
- 未跑完整 `MatchActionServiceIntegrationTest`；完整 suite 仍有既有廣域不穩定。

## 七、結論

attack application stage records 搬移可視為完成。

本步降低 adapter factory 本體閱讀負擔，且未改 attack application stage order、payload 或規則。

## 八、下一步

進入 code review / commit checkpoint。

commit 後建議評估 `AttackArtApplicationAdapterFactory` 的 stage resolver 建立是否能拆成小型 provider，或先收束 attack adapter glue cleanup batch acceptance review。
