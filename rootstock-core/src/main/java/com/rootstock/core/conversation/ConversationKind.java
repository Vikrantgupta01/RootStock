package com.rootstock.core.conversation;

/**
 * Which surface a conversation belongs to. They are kept apart so a grounded
 * RAG thread, a plain chat thread and an agent thread never appear in one
 * another's history -- they answer under different prompts and different
 * grounding rules.
 */
public enum ConversationKind {

	CHAT,
	RAG,
	AGENT
}
