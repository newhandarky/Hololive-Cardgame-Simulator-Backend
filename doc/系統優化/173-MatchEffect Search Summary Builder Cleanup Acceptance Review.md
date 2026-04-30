# MatchEffect Search Summary Builder Cleanup Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本步承接 `172-MatchEffect Search Facade Dead Adapter Cleanup Acceptance Review.md`，繼續 Search / Return / Look family 的低風險 cleanup，先抽出 SEARCH effect 尾端的純摘要組裝。

本步只處理：

- deck bottom reorder candidate map builder
- SEARCH effect summary map builder

不包含：

- search SQL
- candidate filtering
- selected card 移動
- archive remainder 移動
- deck bottom reorder 的執行
- pending decision payload schema

## 二、完成內容

- 新增 `buildDeckBottomReorderCandidate(row, cardInstanceId)`。
- 新增 `buildSearchEffectSummary(...)`。
- `executeSearchEffect(...)` 尾端改委派 summary builder。
- 保留所有 search summary 欄位與原本順序語意。

## 三、Allow / Block 對照

### Allow

- 把 deck bottom reorder candidate 的 Map 組裝集中。
- 把 SEARCH effect summary 的 Map 組裝集中。
- 保留 `criteria` 仍由 `buildCriteriaSummary(criteria)` 建立。

### Block

- 不改任何 `jdbcTemplate.update(...)`。
- 不改 `selectedIds` 計算。
- 不改自動移動單張 reorder candidate 到 deck bottom 的行為。
- 不改前端 summary key。

## 四、測試結果

已通過：

- `./mvnw -q -DskipTests compile`

## 五、大檔尺寸變化

- `MatchEffectService.java`：`11,968` -> `12,006` 行，增加 `38` 行。

## 六、判讀

本步不是行數下降型 cleanup，而是把 SEARCH effect 的執行流程與摘要組裝拆開。

目前沒有本步 blocker。

## 七、結論

MatchEffect search summary builder cleanup 可視為完成。

本步只搬 Map 組裝，不調整 search / return / look family 的行為。

## 八、下一步

進入 code review / commit checkpoint。

commit 後建議繼續 Search / Return / Look family，優先抽出 return-to-hand / return-to-deck-top summary builder，或評估把 search summary builder 搬到 package-private builder 類。
