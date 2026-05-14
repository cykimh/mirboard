package com.mirboard.domain.game.tichu.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TichuMatchParticipantRepository
        extends JpaRepository<TichuMatchParticipant, TichuMatchParticipant.Pk> {

    List<TichuMatchParticipant> findByUserId(long userId);
}
