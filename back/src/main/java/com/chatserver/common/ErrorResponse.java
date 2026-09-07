package com.chatserver.common;

/**
 * Standard error response body shape: { code, message, details }.
 */
public record ErrorResponse(
		String code,
		String message,
		Object details
) {
}
