package com.rootstock.auth.dto;

import java.util.Set;

/** Who the caller is, as the verified token describes them -- what the UI gates itself on. */
public record CurrentUserResponse(String userId, String email, String tenantId, String role, Set<String> groups) {
}
