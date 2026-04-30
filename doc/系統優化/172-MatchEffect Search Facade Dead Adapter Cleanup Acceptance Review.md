# MatchEffect Search Facade Dead Adapter Cleanup Acceptance Review

日期：2026-04-30
狀態：已完成

## 一、本步範圍

本步承接 `171-MatchEffect Bloom Fallback Override Payload Cleanup Acceptance Review.md`，轉向 Search / Return / Look family 的低風險 facade cleanup。

本步只刪除 `MatchEffectService` 內已無呼叫的 search facade adapter。

## 二、完成內容

- 移除未使用的 `loadSearchCandidates(matchId, userId, cardType, levelType, tag, nameContains)` facade adapter。
- 移除未使用的 `matchesBasicSearchCriteria(...)` facade adapter。
- 移除未使用的 `rowTagsContains(...)` facade adapter。
- 移除未使用的 `matchesAnyColor(...)` facade adapter。

## 三、Allow / Block 對照

### Allow

- 刪除 `MatchEffectService` 內 dead single-line delegation。
- 保留 `MatchEffectSearchService` 內真正實作。
- 保留目前仍被 `MatchEffectService` 使用的 `loadCandidatesFromZone(...)` / `filterCandidatesByCriteria(...)` / `matchesSearchCriteria(...)` / `selectSearchCards(...)` / `buildCriteriaSummary(...)` facade。

### Block

- 不改 search SQL。
- 不改 SearchCriteria parser。
- 不改 search / return / look effect payload schema。
- 不改 candidate sorting 或 selection fallback 策略。

## 四、測試結果

已通過：

- `./mvnw -q -DskipTests compile`

## 五、大檔尺寸變化

- `MatchEffectService.java`：`12,003` -> `11,968` 行，減少 `35` 行。

## 六、判讀

這是 dead adapter cleanup，不是 search family service 搬移。

真正的 search matching helper 仍在 `MatchEffectSearchService`，因此不影響查詢與過濾行為。

## 七、結論

MatchEffect search facade dead adapter cleanup 可視為完成。

本步降低 `MatchEffectService` facade 噪音，為後續 Search / Return / Look family cleanup 留出較清楚的邊界。

## 八、下一步

進入 code review / commit checkpoint。

commit 後建議繼續 Search / Return / Look family，優先抽出 search summary / reorder candidate builder 等純摘要組裝邏輯。
