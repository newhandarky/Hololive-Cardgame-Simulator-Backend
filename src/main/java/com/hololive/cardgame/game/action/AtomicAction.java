package com.hololive.cardgame.game.action;

public sealed interface AtomicAction
    permits DrawAction, MoveZoneAction, DamageAction, ReduceLifeAction, SendCheerAction {}
