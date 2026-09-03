package com.hololive.cardgame.service;

/**
 * 對戰行動進入 application layer 的 typed command marker。
 */
public sealed interface MatchCommand permits ConcedeMatchCommand, DrawTurnCommand, SendTurnCheerCommand {
}
