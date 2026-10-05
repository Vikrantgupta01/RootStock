package com.rootstock.agent.dto;

/**
 * One Act/Observe step of a ReAct run: the agent asked for {@code tool} with
 * {@code input}, and got {@code observation} back.
 *
 * @param iteration   which Reason step requested it, from 1
 * @param thought     what the model wrote alongside the tool call, if anything;
 *                    set on the first call of an iteration only, since several
 *                    calls requested together share one thought
 * @param input       the tool arguments as the model sent them (JSON)
 * @param observation the tool's result, abbreviated -- the model saw it in full
 */
public record AgentStep(int iteration, String thought, String tool, String input, String observation) {
}
