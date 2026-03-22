package com.hololive.cardgame.service.effect;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 統一組裝 Gift trigger 的摘要 payload。
 *
 * <p>目前 Gift trigger 在後端會產生兩種很接近的資料：
 *
 * <p>1. deferred preview：建立 `TRIGGER_EFFECT_CONFIRM` 前要先描述「若確認會做什麼」
 * <p>2. executed summary：使用者確認後，真正執行完成要寫入 action log 的結果
 *
 * <p>兩者的欄位高度重疊，若每次都在 `MatchEffectService` 直接手工拼 map，很容易在後續新增欄位時
 * 出現某一條路徑漏欄位、欄位命名不一致、或 `partiallyResolved` 判斷不一致的問題。
 *
 * <p>因此這個 service 專責做一件事：把已完成的 trigger 判斷結果與 effect execution result 組裝成
 * 對外可用的摘要結構。它不負責：
 *
 * <p>- 決定 Gift 是否應該觸發
 * <p>- 決定要執行哪些 effect
 * <p>- 寫資料庫或更新對戰狀態
 */
public class GiftTriggerPreviewService {

    /**
     * 建立單一 Gift 持有者的摘要 payload。
     *
     * <p>這裡只組裝資料，不重新計算規則條件。呼叫方必須先保證：
     *
     * <p>- trigger type 已確定
     * <p>- holder 已通過資格判斷
     * <p>- giftText 已解析完成
     * <p>- execution 已是最終結果
     *
     * <p>`deferred=true` 代表這是一筆預覽摘要，前端或 pending context 可以據此知道目前仍需確認。
     * 不是所有 preview 都要靠 action type 區分，所以這個旗標要保留在 payload 內。
     */
    public Map<String, Object> buildTriggerSummary(
        String normalizedTriggerType,
        Long holderHolomemId,
        Long holderCardInstanceId,
        String holderCardId,
        String holderZone,
        Long sourceCardInstanceId,
        Long triggerTargetCardInstanceId,
        String giftText,
        GiftExecutionSummary execution,
        boolean deferred
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("triggerType", normalizedTriggerType);
        summary.put("giftHolderHolomemId", holderHolomemId);
        summary.put("giftHolderCardInstanceId", holderCardInstanceId);
        summary.put("giftHolderCardId", holderCardId);
        summary.put("giftHolderZone", holderZone);
        summary.put("sourceCardInstanceId", sourceCardInstanceId);
        summary.put("triggerTargetCardInstanceId", triggerTargetCardInstanceId);
        summary.put("rawText", giftText);
        summary.put("requestedEffects", execution.requestedEffects());
        summary.put("executedEffects", execution.executedEffects());
        summary.put("unsupportedEffects", execution.unsupportedEffects());
        summary.put("skippedEffects", execution.skippedEffects());
        summary.put(
            "partiallyResolved",
            !execution.skippedEffects().isEmpty() || !execution.unsupportedEffects().isEmpty()
        );
        if (deferred) {
            summary.put("deferred", true);
        }
        return summary;
    }
}
