package com.hololive.cardgame.repository;

import com.hololive.cardgame.entity.DeckCardEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeckCardRepository extends JpaRepository<DeckCardEntity, Long> {

    List<DeckCardEntity> findByDeckIdOrderByCardIdAsc(Long deckId);

    Optional<DeckCardEntity> findByDeckIdAndCardId(Long deckId, String cardId);

    void deleteByDeckIdAndCardId(Long deckId, String cardId);

    void deleteByDeckId(Long deckId);
}
