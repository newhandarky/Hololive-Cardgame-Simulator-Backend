# Attack Performance State Updater Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本步延續 attack adapter glue cleanup，處理 `AttackArtApplicationAdapterFactory` 內 rest-and-save performance state 的副作用膠水。

本步只抽出：

- 攻擊者 Holomem 設為 rested
- 查詢是否仍有可使用藝能的 performance action
- 將 match phase 保持為 `PERFORMANCE`
- touch updated_at 並 save match

不包含：

- attack cost / target / damage 規則
- action log payload 組裝
- `ATTACK_ART` action log 寫入時機
- finish check timing
- pending decision timing
- `AttackRestAndPayloadService` payload shape

## 二、完成內容

- 新增 `AttackPerformanceStateUpdater`。
- `AttackArtApplicationAdapterFactory.AttackApplicationRestAndPayloadResolver` 改委派 updater 執行 rest / phase save。
- 保留 rest-and-save 在 `STAGE_REST_AND_PAYLOAD` 內，且仍發生在 action log 與 finish check 之前。
- 新增 `AttackPerformanceStateUpdaterTest`，鎖定成功路徑與 stale rest update 失敗路徑。

## 三、Allow / Block 對照

### Allow

- 把 rest / phase save side effect 從 adapter factory stage resolver 移出。
- updater 回傳 `hasNextPerformanceAction`，維持既有 payload input。
- factory constructor 可用既有依賴組裝 updater，避免擴大外部 wiring。

### Block

- 不改 `ATTACK_ART` action log timing。
- 不改 finish check timing。
- 不改 match phase target value。
- 不改 rested update SQL 條件。
- 不改 stale update 的錯誤訊息。
- 不改 `AttackRestAndPayloadContext` / payload shape。
- 不碰完整 attack 規則主流程。

## 四、測試結果

已通過：

- `./mvnw -q -Dtest=AttackPerformanceStateUpdaterTest,AttackArtApplicationAdapterFactoryTest test`

測試覆蓋：

- rest update 發生在 availability check / touch / save 之前
- match phase 更新為 `PERFORMANCE`
- updater 回傳 `hasNextPerformanceAction`
- stale rest update 直接丟出既有錯誤，且不 touch / save
- adapter factory 仍維持 rest/save -> action log -> finish check 順序

## 五、大檔尺寸變化

- `AttackArtApplicationAdapterFactory.java`：`740` -> `715` 行，減少 `25` 行。
- 新增 `AttackPerformanceStateUpdater.java`：`55` 行。
- 新增 `AttackPerformanceStateUpdaterTest.java`：`105` 行。
- `MatchActionService.java`：未變更。

## 六、剩餘缺口

目前沒有本步 blocker。

保留缺口：

- `AttackArtApplicationAdapterFactory` 仍有多個 stage resolver inner class，後續可繼續拆 factory/provider。
- 未跑完整 `MatchActionServiceIntegrationTest`；完整 suite 仍有既有廣域不穩定。

## 七、結論

attack performance state updater extraction 可視為完成。

本步把 rest / phase save 副作用從 adapter factory stage resolver 拆出，並保留原本 action log 與 finish check 順序。

## 八、下一步

進入 code review / commit checkpoint。

commit 後建議繼續盤點 `AttackArtApplicationAdapterFactory` 內 stage result records 與 stage resolver 建立是否能拆成較小邊界。
