package com.rootstock.core.conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

	/** Always looked up with the owner, so one person's id can never load another's thread. */
	Optional<Conversation> findByIdAndTenantIdAndUserId(UUID id, String tenantId, String userId);

	List<Conversation> findByTenantIdAndUserIdAndKindOrderByUpdatedAtDesc(
			String tenantId, String userId, ConversationKind kind);
}
