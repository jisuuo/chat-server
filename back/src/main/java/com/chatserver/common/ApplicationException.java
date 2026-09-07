package com.chatserver.common;

/**
 * Base type for application-specific exceptions. Concrete exception
 * types are added as needed in later implementation phases.
 */
public class ApplicationException extends RuntimeException {

	public ApplicationException(String message) {
		super(message);
	}

	public ApplicationException(String message, Throwable cause) {
		super(message, cause);
	}

}
