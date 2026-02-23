package com.hololive.cardgame.repository;

import com.hololive.cardgame.entity.Card;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CardRepository extends JpaRepository<Card, String> {

    List<Card> findAllByOrderByCardIdAsc();

    List<Card> findByCardTypeOrderByCardIdAsc(String cardType);

    List<Card> findByNameContainingIgnoreCaseOrderByCardIdAsc(String keyword);

    List<Card> findByCardIdIn(Collection<String> cardIds);

    @Query(
        value = """
            SELECT mc.card_id
            FROM member_cards mc
            WHERE mc.passive_effect_json::text LIKE U&'%\\4F55\\679A\\3067\\3082\\5165\\308C\\3089\\308C\\308B%'
            """,
        nativeQuery = true
    )
    List<String> findUnlimitedMainDeckCardIds();
}
