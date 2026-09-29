package com.rootstock.auth;

/** Login was rejected by Cognito -- wrong password, no such user, or an unconfirmed/disabled account. */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException(String message) {
		super(message);
	}
}
