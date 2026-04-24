package com.hololive.cardgame.service;

record MatchGiftTriggerSourceContext(
    String cardId,
    String cardName,
    String levelType,
    String stageZone,
    String tagsJson,
    String artName
) {}
