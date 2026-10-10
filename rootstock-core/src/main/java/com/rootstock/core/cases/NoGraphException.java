package com.rootstock.core.cases;

/** No graph to run the case with: none configured, or not the one named. */
public class NoGraphException extends RuntimeException {

	public NoGraphException(String message) {
		super(message);
	}
}
