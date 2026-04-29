package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FollowupInteractionContextBuilderTest {

    private final FollowupInteractionContextBuilder builder = new FollowupInteractionContextBuilder();

    @Test
    void buildFollowupInteractionContextShouldBuildLookTopDeckContext() {
        FollowupInteractionContext context = builder.buildFollowupInteractionContext(
            10L,
            effectSummary(
                Map.of(
                    "effectType",
                    "LOOK_TOP_DECK",
                    "applied",
                    true,
                    "lookedCardInstanceId",
                    "101",
                    "lookedCardId",
                    "HBP99-001"
                )
            ),
            this::candidate
        );

        assertThat(context.decisionType()).isEqualTo("LOOK_TOP_DECK");
        assertThat(context.title()).isEqualTo("查看牌庫頂");
        assertThat(context.minSelect()).isZero();
        assertThat(context.maxSelect()).isEqualTo(1);
        assertThat(context.candidateCardInstanceIds()).containsExactly(101L);
        assertThat(context.placementOptions()).containsExactly("TOP", "BOTTOM");
        assertThat(context.lookedCardInstanceId()).isEqualTo(101L);
        assertThat(context.lookedCardId()).isEqualTo("HBP99-001");
        assertThat(context.cards()).containsExactly(candidate(10L, 10L, 101L, "DECK", "HBP99-001"));
    }

    @Test
    void buildFollowupInteractionContextShouldBuildLookOpponentHandContext() {
        FollowupInteractionContext context = builder.buildFollowupInteractionContext(
            10L,
            effectSummary(
                Map.of(
                    "effectType",
                    "LOOK_OPPONENT_HAND",
                    "applied",
                    "true",
                    "lookedUserId",
                    20L,
                    "lookedCards",
                    List.of(
                        Map.of("cardInstanceId", 201L, "cardId", "HBP99-002"),
                        Map.of("cardInstanceId", 0L, "cardId", "BAD")
                    )
                )
            ),
            this::candidate
        );

        assertThat(context.decisionType()).isEqualTo("LOOK_OPPONENT_HAND");
        assertThat(context.title()).isEqualTo("查看對手手牌");
        assertThat(context.message()).isEqualTo("以下為本次效果可查看的對手手牌。");
        assertThat(context.candidateCardInstanceIds()).containsExactly(201L);
        assertThat(context.cards()).containsExactly(
            candidate(10L, 20L, 201L, "HAND", "HBP99-002"),
            candidate(10L, 20L, 0L, "HAND", "BAD")
        );
    }

    @Test
    void buildFollowupInteractionContextShouldBuildLookHolopowerContext() {
        FollowupInteractionContext context = builder.buildFollowupInteractionContext(
            10L,
            effectSummary(
                Map.of(
                    "effectType",
                    "LOOK_HOLOPOWER",
                    "applied",
                    true,
                    "lookedCards",
                    List.of(Map.of("cardInstanceId", 301L, "cardId", "HBP99-003"))
                )
            ),
            this::candidate
        );

        assertThat(context.decisionType()).isEqualTo("LOOK_HOLOPOWER");
        assertThat(context.title()).isEqualTo("查看 Holopower");
        assertThat(context.message()).isEqualTo("以下為本次效果可查看的 Holopower。");
        assertThat(context.candidateCardInstanceIds()).containsExactly(301L);
        assertThat(context.cards()).containsExactly(candidate(10L, 10L, 301L, "HOLOPOWER", "HBP99-003"));
    }

    @Test
    void buildFollowupInteractionContextShouldBuildReorderDeckBottomContextForSearch() {
        FollowupInteractionContext context = builder.buildFollowupInteractionContext(
            10L,
            effectSummary(
                Map.of(
                    "effectType",
                    "SEARCH",
                    "applied",
                    true,
                    "requiresDeckBottomReorder",
                    true,
                    "deckBottomReorderCandidates",
                    List.of(
                        Map.of("cardInstanceId", 401L, "cardId", "HBP99-004"),
                        Map.of("cardInstanceId", 402L, "cardId", "HBP99-005")
                    )
                )
            ),
            this::candidate
        );

        assertThat(context.decisionType()).isEqualTo("REORDER_DECK_BOTTOM");
        assertThat(context.title()).isEqualTo("排序牌庫底");
        assertThat(context.minSelect()).isEqualTo(2);
        assertThat(context.maxSelect()).isEqualTo(2);
        assertThat(context.candidateCardInstanceIds()).containsExactly(401L, 402L);
        assertThat(context.cards()).containsExactly(
            candidate(10L, 10L, 401L, "DECK", "HBP99-004"),
            candidate(10L, 10L, 402L, "DECK", "HBP99-005")
        );
    }

    @Test
    void buildFollowupInteractionContextShouldSkipInvalidRows() {
        FollowupInteractionContext context = builder.buildFollowupInteractionContext(
            10L,
            effectSummary(
                Map.of("effectType", "LOOK_TOP_DECK", "applied", false),
                Map.of("effectType", "LOOK_TOP_DECK", "applied", true, "lookedCardInstanceId", 101L),
                Map.of(
                    "effectType",
                    "REORDER_DECK_BOTTOM",
                    "applied",
                    true,
                    "requiresDeckBottomReorder",
                    true,
                    "deckBottomReorderCandidates",
                    List.of(Map.of("cardInstanceId", 401L, "cardId", "HBP99-004"))
                )
            ),
            this::candidate
        );

        assertThat(context).isNull();
    }

    private Map<String, Object> effectSummary(Map<String, Object>... executedEffects) {
        return Map.of("executedEffects", List.of(executedEffects));
    }

    private Map<String, Object> candidate(
        Long viewerUserId,
        Long ownerUserId,
        Long cardInstanceId,
        String fallbackZone,
        String fallbackCardId
    ) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("viewerUserId", viewerUserId);
        candidate.put("ownerUserId", ownerUserId);
        candidate.put("cardInstanceId", cardInstanceId);
        candidate.put("zone", fallbackZone);
        candidate.put("cardId", fallbackCardId);
        return candidate;
    }
}
