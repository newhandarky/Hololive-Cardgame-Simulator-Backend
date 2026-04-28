package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.entity.MatchEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MatchTimestampServiceTest {

    private final MatchTimestampService service = new MatchTimestampService();

    @Test
    void touchUpdatedAtShouldRefreshMatchTimestamp() {
        MatchEntity match = new MatchEntity();
        LocalDateTime previous = LocalDateTime.now().minusDays(1);
        match.setUpdatedAt(previous);

        service.touchUpdatedAt(match);

        assertThat(match.getUpdatedAt()).isAfter(previous);
    }
}
