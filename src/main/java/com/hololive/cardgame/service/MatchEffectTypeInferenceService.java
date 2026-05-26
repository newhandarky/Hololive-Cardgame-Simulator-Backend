package com.hololive.cardgame.service;

import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.util.StringUtils;

final class MatchEffectTypeInferenceService {

    private final EffectTextParser effectTextParser;

    MatchEffectTypeInferenceService(EffectTextParser effectTextParser) {
        this.effectTextParser = effectTextParser;
    }

    List<String> inferEffectTypes(String effectText) {
        Set<String> effectTypes = new LinkedHashSet<>();
        String text = effectTextParser.normalizeDigits(effectText == null ? "" : effectText);
        if (!StringUtils.hasText(text)) {
            effectTypes.add("UNIMPLEMENTED");
            return new ArrayList<>(effectTypes);
        }
        boolean archiveReplacementToHand = text.contains("アーカイブするかわりに手札に加えられる");

        if (text.contains("手札に加える")) {
            effectTypes.add("SEARCH");
        }
        if (archiveReplacementToHand) {
            effectTypes.add("REPLACE_ARCHIVE_WITH_HAND");
            effectTypes.remove("SEARCH");
        }
        if (text.contains("手札に戻")) {
            effectTypes.add("RETURN_TO_HAND");
        }
        if (text.contains("デッキの上に戻")) {
            effectTypes.add("RETURN_TO_DECK_TOP");
        }
        if (text.contains("引く")) {
            effectTypes.add("DRAW");
        }
        if (text.contains("エール") && text.contains("送")) {
            effectTypes.add("ADD_CHEER");
        }
        if (
            text.contains("付け替え")
                || text.contains("割り振って付け")
                || text.contains("付けられる")
                || text.contains("付ける")
        ) {
            effectTypes.add("REATTACH");
        }
        if (text.contains("ステージに出せ") || text.contains("ステージに出す")) {
            effectTypes.add("SUMMON_TO_STAGE");
        }
        if (text.contains("公開し、アーカイブ")) {
            effectTypes.add("REVEAL_TO_ARCHIVE");
        }
        if (text.contains("アーカイブのホロメンを使ってBloom")) {
            effectTypes.add("BLOOM_FROM_ARCHIVE");
        }
        if (text.contains("エールデッキの下に戻")) {
            effectTypes.add("RETURN_CHEER_TO_DECK_BOTTOM");
        }
        if (text.contains("エールデッキに戻")) {
            effectTypes.add("RETURN_CHEER_TO_DECK_BOTTOM");
        }
        if (text.contains("エール") && (text.contains("アーカイブできる") || text.contains("アーカイブする"))) {
            effectTypes.add("REMOVE_CHEER");
        }
        if (
            text.contains("重なっているホロメン")
                && (text.contains("アーカイブできる") || text.contains("アーカイブする"))
        ) {
            effectTypes.add("ARCHIVE_STACK_CARD");
        }
        if (text.contains("手札") && (text.contains("アーカイブする") || text.contains("アーカイブできる"))) {
            effectTypes.add("DISCARD_HAND");
        }
        if (text.contains("お休みさせる")) {
            effectTypes.add("REST");
        }
        if (text.contains("センターホロメン") && text.contains("バックホロメン") && text.contains("交代")) {
            effectTypes.add("SWAP_CENTER_BACK");
        }
        if (text.contains("ホロパワーにする")) {
            effectTypes.add("MOVE_TO_HOLOPOWER");
        }
        if (text.contains("ダウンさせる") && text.contains("ダウンしても相手のライフは減らない")) {
            effectTypes.add("DOWN_NO_LIFE");
        }
        if (
            text.contains("ダウンさせる")
                && text.contains("ライフ")
                && (text.contains("追加") || text.contains("さらに"))
        ) {
            effectTypes.add("DOWN_EXTRA_LIFE");
        }
        if (text.contains("バトンタッチに必要な無色") && (text.contains("+") || text.contains("＋"))) {
            effectTypes.add("BATON_TOUCH_COST_MODIFIER");
        }
        if (
            text.contains("できない")
                && (text.contains("バトンタッチ")
                    || text.contains("移動")
                    || text.contains("交代")
                    || text.contains("Bloom")
                    || text.contains("ブルーム"))
        ) {
            effectTypes.add("ACTION_LOCK");
        }
        if (text.contains("もう1回Bloomできる")) {
            effectTypes.add("ALLOW_EXTRA_BLOOM");
        }
        if (text.contains("デッキの上から1枚を見る")) {
            effectTypes.add("LOOK_TOP_DECK");
        }
        if (
            text.contains("相手")
                && text.contains("手札")
                && (text.contains("見る") || text.contains("見"))
        ) {
            effectTypes.add("LOOK_OPPONENT_HAND");
        }
        if (
            text.contains("ホロパワー")
                && (text.contains("見る") || text.contains("見"))
        ) {
            effectTypes.add("LOOK_HOLOPOWER");
        }
        if (text.contains("交代できる")) {
            effectTypes.add("SWAP_WITH_COLLAB");
        }
        if (text.contains("移動させる")) {
            effectTypes.add("MOVE_ZONE");
        }
        if (text.contains("回復")) {
            effectTypes.add("HEAL");
        }
        if (text.contains("ダメージ")) {
            effectTypes.add("DAMAGE");
        }
        if (text.contains("勝利") || text.contains("敗北") || text.contains("引き分け")) {
            effectTypes.add("MATCH_RESULT");
        }
        if (text.contains("アーツ")) {
            if (text.contains("-")) {
                effectTypes.add("DEBUFF");
            } else if (text.contains("+")) {
                effectTypes.add("BUFF");
            }
        }
        if (effectTypes.isEmpty()) {
            effectTypes.add("UNIMPLEMENTED");
        }
        return new ArrayList<>(effectTypes);
    }

    String inferTargetType(String effectType) {
        return switch (effectType) {
            case "DAMAGE", "DEBUFF", "MOVE_ZONE", "REST", "DOWN_NO_LIFE", "DOWN_EXTRA_LIFE" -> "ENEMY";
            default -> "SELF";
        };
    }
}
