package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourcelessGiftPendingDecisionCreatorTest {

    private final GiftPendingDecisionCreator giftPendingDecisionCreator = mock(GiftPendingDecisionCreator.class);
    private final SourcelessGiftPendingDecisionCreator creator = new SourcelessGiftPendingDecisionCreator(
        giftPendingDecisionCreator
    );

    @Test
    void createShouldDelegateWithoutSourceCard() {
        List<Map<String, Object>> giftEffects = List.of(Map.of("triggerType", "MAIN_STEP_SELF"));
        FollowupInteractionDecision decision = new FollowupInteractionDecision(501L, "TRIGGER_EFFECT_CONFIRM");
        when(giftPendingDecisionCreator.createWithGiftTriggerInteractionCards(
            100L,
            10L,
            null,
            null,
            giftEffects,
            4
        )).thenReturn(decision);

        FollowupInteractionDecision result = creator.create(100L, 10L, giftEffects, 4);

        assertThat(result).isEqualTo(decision);
        verify(giftPendingDecisionCreator).createWithGiftTriggerInteractionCards(
            100L,
            10L,
            null,
            null,
            giftEffects,
            4
        );
    }
}
