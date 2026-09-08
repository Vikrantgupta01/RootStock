package com.rootstock.common;

/**
 * Thrown when an uploaded file's content type is not accepted. Mapped to HTTP 415
 * by {@link GlobalExceptionHandler}.
 */
public class UnsupportedContentTypeException extends RuntimeException {

	public UnsupportedContentTypeException(String message) {
		super(message);
	}
}
