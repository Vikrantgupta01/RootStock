package com.rootstock.core.agent;

import java.lang.reflect.Type;
import org.springframework.ai.tool.execution.ToolCallResultConverter;

/**
 * Hands a tool's String result to the model as-is. The default converter
 * JSON-encodes it, which turns a passage list into one quoted line full of
 * {@code \n} escapes -- harder for the model to read and for a person to
 * inspect in the returned steps.
 */
public class PlainTextResultConverter implements ToolCallResultConverter {

	@Override
	public String convert(Object result, Type returnType) {
		return result == null ? "" : result.toString();
	}
}
