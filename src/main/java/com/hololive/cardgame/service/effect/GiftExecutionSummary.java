package com.hololive.cardgame.service.effect;

import java.util.List;
import java.util.Map;

/**
 * 描述單一 Gift 觸發後，效果解析與執行的結果。
 *
 * <p>這個模型被抽成獨立 record 的原因是，Gift trigger 目前有兩種常見使用情境：
 *
 * <p>1. 只做 preview，不真正執行效果
 * <p>2. 在確認後真正執行效果
 *
 * <p>兩者都需要一份共通的結果結構，讓外層可以一致地組裝 action payload、pending context
 * 與前端顯示資料，而不必知道效果是 preview 還是 executed。
 */
public record GiftExecutionSummary(
    List<String> requestedEffects,
    List<Map<String, Object>> executedEffects,
    List<String> unsupportedEffects,
    List<Map<String, Object>> skippedEffects
) {}
