package com.rootstock.rag.query.dto;

import java.util.List;
import java.util.UUID;

/**
 * @param conversationId  the thread this answer belongs to -- send it back to ask a follow-up
 * @param retrievalQuery  what was actually searched for. Differs from the question asked
 *                        whenever a follow-up had to be rewritten into a standalone query
 *                        ("and its price?" retrieves nothing on its own), and is surfaced
 *                        so that rewrite is inspectable rather than invisible.
 */
public record RagQueryResponse(
        String answer,
        boolean grounded,
        List<Citation> citations,
        UUID conversationId,
        String retrievalQuery,
        UUID profileId,
        String profileName,
        int profileVersionNo,
        List<UUID> usedVersionIds) {
}
