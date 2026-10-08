package com.rootstock.core.tools;

/**
 * Whether a tool reads from or writes to a client system. Decides which token a
 * call carries: write access is requested only when a write is actually made,
 * never held "just in case".
 */
public enum ToolAccess {
	READ,
	WRITE
}
