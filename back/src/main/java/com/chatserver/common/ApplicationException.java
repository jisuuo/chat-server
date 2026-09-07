package com.chatserver.common;

/**
 * Base type for application-specific exceptions. Subclasses supply a
 * stable errorCode used both in the HTTP error body and for client
 * branching (docs/api-design.md common error shape).
 */
public class ApplicationException extends RuntimeException {

	private final String errorCode;

	protected ApplicationException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	protected ApplicationException(String errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public String getErrorCode() {
		return errorCode;
	}

}
