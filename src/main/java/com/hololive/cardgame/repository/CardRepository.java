package com.hololive.cardgame.repository;

import com.hololive.cardgame.entity.Card;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardRepository extends JpaRepository<Card, String> {

    List<Card> findAllByOrderByCardIdAsc();

    List<Card> findByCardTypeOrderByCardIdAsc(String cardType);

    List<Card> findByNameContainingIgnoreCaseOrderByCardIdAsc(String keyword);
}
