package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.service.effect.SearchCriteria;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.Map;
import org.springframework.util.StringUtils;

class MatchAddCheerSourceResolverService {

    private final SearchCriteriaParser searchCriteriaParser;
    private final MatchCheerCandidateQueryService cheerCandidateQueryService;

    MatchAddCheerSourceResolverService(
        SearchCriteriaParser searchCriteriaParser,
        MatchCheerCandidateQueryService cheerCandidateQueryService
    ) {
        this.searchCriteriaParser = searchCriteriaParser;
        this.cheerCandidateQueryService = cheerCandidateQueryService;
    }

    /**
     * 從文案推斷 ADD_CHEER 的來源區。
     *
     * 目前先補齊專案裡最常見且已經有官方卡需求的兩種來源：
     * - アーカイブのエール
     * - エールデッキの上から
     *
     * 若文案沒有明示，仍保留原本的 CHEER_DECK > ARCHIVE > HAND fallback。
     */
    Map<String, Object> resolvePreferredAddCheerSource(Long matchId, Long userId, String rawText) {
        String sourceClause = extractAddCheerSourceClause(rawText);
        SearchCriteria sourceCriteria = resolveSearchCriteriaFromRawText(sourceClause);

        if (StringUtils.hasText(sourceClause) && sourceClause.contains("アーカイブの")) {
            return cheerCandidateQueryService.findCheerCardFromZone(matchId, userId, "ARCHIVE", sourceCriteria);
        }
        if (StringUtils.hasText(sourceClause) && sourceClause.contains("エールデッキ")) {
            return cheerCandidateQueryService.findCheerCardFromZone(matchId, userId, "CHEER_DECK", sourceCriteria);
        }
        return cheerCandidateQueryService.findAttachableCheerCard(matchId, userId);
    }

    /**
     * 從送 Cheer 效果段中擷取來源描述。
     *
     * <p>例如：
     * - `自分のアーカイブの黄エール1枚を自分の〈虎金妃笑虎〉に送る`
     * 這裡真正決定來源的是前半句 `自分のアーカイブの黄エール1枚`。
     */
    String extractAddCheerSourceClause(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        String clause = rawText;
        int sourceStart = -1;
        String[] markers = {
            "自分のエールデッキ",
            "相手のエールデッキ",
            "エールデッキ",
            "自分のアーカイブの",
            "相手のアーカイブの",
            "アーカイブの"
        };
        for (String marker : markers) {
            int index = rawText.lastIndexOf(marker);
            if (index > sourceStart) {
                sourceStart = index;
            }
        }
        if (sourceStart >= 0) {
            clause = rawText.substring(sourceStart);
        }
        int splitIndex = clause.indexOf('を');
        return splitIndex < 0 ? clause.trim() : clause.substring(0, splitIndex).trim();
    }

    /**
     * 直接把一小段 raw text 轉成 SearchCriteria。
     */
    private SearchCriteria resolveSearchCriteriaFromRawText(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return SearchCriteria.empty();
        }
        ObjectNode probe = JsonNodeFactory.instance.objectNode();
        probe.put("rawText", rawText);
        return searchCriteriaParser.resolveSearchCriteria(probe);
    }

}
