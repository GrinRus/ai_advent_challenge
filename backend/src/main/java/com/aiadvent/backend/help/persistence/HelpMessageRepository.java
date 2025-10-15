package com.aiadvent.backend.help.persistence;

import com.aiadvent.backend.help.domain.HelpMessage;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HelpMessageRepository extends JpaRepository<HelpMessage, Long> {

  Optional<HelpMessage> findTopByOrderByIdDesc();
}
