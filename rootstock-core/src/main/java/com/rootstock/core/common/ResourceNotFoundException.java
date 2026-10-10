package com.rootstock.core.common;

import com.rootstock.runtime.common.GlobalExceptionHandler;

/**
 * Thrown when a requested resource does not exist. Mapped to HTTP 404 by
 * {@link GlobalExceptionHandler}.
 */
public class ResourceNotFoundException extends RuntimeException {

	public ResourceNotFoundException(String message) {
		super(message);
	}

	public static ResourceNotFoundException of(String resource, Object id) {
		return new ResourceNotFoundException(resource + " " + id + " was not found.");
	}
}
