package com.hololive.cardgame.service;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

@Service
public class RandomDiceService implements DiceService {

    @Override
    public int rollD6() {
        return ThreadLocalRandom.current().nextInt(1, 7);
    }
}

