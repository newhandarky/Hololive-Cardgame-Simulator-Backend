package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import java.time.LocalDateTime;

class MatchTimestampService {

    void touchUpdatedAt(MatchEntity match) {
        match.setUpdatedAt(LocalDateTime.now());
    }
}
