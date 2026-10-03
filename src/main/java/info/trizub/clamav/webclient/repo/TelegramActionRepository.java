package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.TelegramAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TelegramActionRepository extends JpaRepository<TelegramAction, String> {
    List<TelegramAction> findByChatIdAndMessageIdOrderByCreatedAtAsc(String chatId, Long messageId);
    Optional<TelegramAction> findFirstByCommandId(Long commandId);

    @Transactional
    long deleteByCreatedAtBefore(Instant before);
}
