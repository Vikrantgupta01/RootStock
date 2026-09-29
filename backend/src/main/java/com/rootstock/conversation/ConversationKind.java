package com.rootstock.conversation;

/**
 * Which surface a conversation belongs to. The two are kept apart so a grounded
 * RAG thread and a plain chat thread never appear in one another's history --
 * they answer under different prompts and different grounding rules.
 */
public enum ConversationKind {

	CHAT,
	RAG
}
