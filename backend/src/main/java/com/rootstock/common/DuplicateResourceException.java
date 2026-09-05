package com.rootstock.common;

/**
 * Thrown when creating or updating a resource would violate a uniqueness
 * constraint. Mapped to HTTP 409 by {@link GlobalExceptionHandler}.
 */
public class DuplicateResourceException extends RuntimeException {

	public DuplicateResourceException(String message) {
		super(message);
	}
}
