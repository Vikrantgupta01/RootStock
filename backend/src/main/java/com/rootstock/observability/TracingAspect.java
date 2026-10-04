package com.rootstock.observability;

import com.rootstock.chat.dto.ChatRequest;
import com.rootstock.chat.dto.ChatResponse;
import com.rootstock.conversation.Conversation;
import com.rootstock.rag.query.dto.RagQueryRequest;
import com.rootstock.rag.query.dto.RagQueryResponse;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;

/**
 * All of this application's tracing, in one place and outside the code it
 * observes.
 *
 * <p>The alternative -- opening observations inline in the controller and the
 * query service -- works, but it puts try/finally scaffolding and attribute
 * plumbing into methods whose job is answering questions, and it spreads the
 * knowledge of what Langfuse wants across the codebase. Here, {@code ChatController},
 * {@code RagQueryService} and {@code BedrockKnowledgeBaseClient} are entirely
 * unaware they are traced.
 *
 * <p>Everything the spans need is derived from arguments and return values. That
 * is not a coincidence: {@code RagQueryResponse} already carries the
 * conversation id, the profile, the citations and the rewritten retrieval query,
 * because those are the things the API itself found worth returning.
 *
 * <p>Advice runs on the caller's thread, so {@code TenantContext} and
 * {@code AuthContext} -- ThreadLocals the servlet filter clears when the request
 * ends -- are still bound when {@link RequestTrace#start} reads them.
 */
@Aspect
@Component
public class TracingAspect {

	private static final int SNIPPET_CHARS = 300;

	private final RequestTrace trace;
	private final ObservationRegistry observations;

	public TracingAspect(RequestTrace trace, ObservationRegistry observations) {
		this.trace = trace;
		this.observations = observations;
	}

	// ---- pointcuts ----------------------------------------------------------

	@Pointcut("execution(* com.rootstock.chat.ChatController.chat(..))")
	void plainChat() {
	}

	@Pointcut("execution(* com.rootstock.chat.ChatController.chatStream(..))")
	void streamingChat() {
	}

	@Pointcut("execution(* com.rootstock.rag.query.RagQueryService.query(..))")
	void ragQuery() {
	}

	@Pointcut("execution(* com.rootstock.rag.vector.BedrockKnowledgeBaseClient.retrieve(..))")
	void knowledgeBaseRetrieval() {
	}

	@Pointcut("execution(* com.rootstock.conversation.ConversationService.resolve(..))")
	void conversationResolved() {
	}

	// ---- root spans ---------------------------------------------------------

	/**
	 * One trace per answered question. The session id is not available when the
	 * span opens -- the conversation is resolved inside the method -- so it is
	 * stamped by {@link #stampSession}, and the rest is read off the response.
	 */
	@Around("ragQuery()")
	public Object traceRagQuery(ProceedingJoinPoint joinPoint) throws Throwable {
		RagQueryRequest request = argument(joinPoint, RagQueryRequest.class);
		Observation observation = trace.start("answer-question", RequestTrace.SURFACE_RAG, null);
		if (request != null) {
			RequestTrace.input(observation, request.question());
		}
		try (Observation.Scope ignored = observation.openScope()) {
			Object result = joinPoint.proceed();
			if (result instanceof RagQueryResponse response) {
				describeRagAnswer(observation, request, response);
			}
			return result;
		}
		catch (Throwable failure) {
			observation.error(failure);
			throw failure;
		}
		finally {
			observation.stop();
			TraceIdentity.clear();
		}
	}

	@Around("plainChat()")
	public Object traceChat(ProceedingJoinPoint joinPoint) throws Throwable {
		Observation observation = trace.start("chat-response", RequestTrace.SURFACE_CHAT, null);
		RequestTrace.input(observation, message(joinPoint));
		try (Observation.Scope ignored = observation.openScope()) {
			Object result = joinPoint.proceed();
			if (result instanceof ChatResponse response) {
				RequestTrace.output(observation, response.reply());
			}
			return result;
		}
		catch (Throwable failure) {
			observation.error(failure);
			throw failure;
		}
		finally {
			observation.stop();
			TraceIdentity.clear();
		}
	}

	/**
	 * Streaming needs its own advice: the method returns as soon as the
	 * {@link Flux} is assembled, long before any token is produced, so stopping
	 * the span on return would record a few microseconds of assembly instead of
	 * the generation. {@code doFinally} covers completion, error and cancellation
	 * alike -- a browser navigating away mid-stream would otherwise leave the
	 * span open indefinitely.
	 */
	@Around("streamingChat()")
	public Object traceChatStream(ProceedingJoinPoint joinPoint) throws Throwable {
		Observation observation = trace.start("chat-response", RequestTrace.SURFACE_CHAT, null);
		RequestTrace.input(observation, message(joinPoint));
		RequestTrace.metadata(observation, "streaming", "true");
		Object result;
		try (Observation.Scope ignored = observation.openScope()) {
			result = joinPoint.proceed();
		}
		catch (Throwable failure) {
			observation.error(failure);
			observation.stop();
			throw failure;
		}
		if (!(result instanceof Flux<?> flux)) {
			observation.stop();
			return result;
		}
		StringBuilder answer = new StringBuilder();
		return flux
				.doOnNext(chunk -> answer.append(chunk))
				.doOnComplete(() -> RequestTrace.output(observation, answer.toString()))
				.doOnError(observation::error)
				.doFinally(signal -> {
					observation.stop();
					TraceIdentity.clear();
				});
	}

	// ---- enrichment ---------------------------------------------------------

	/**
	 * A conversation is exactly a Langfuse session, and it is resolved inside the
	 * traced call rather than passed into it. Stamping it on whatever observation
	 * is current keeps the controller and the query service from having to hand it
	 * over -- and covers the streaming path, where no return value carries it.
	 */
	@AfterReturning(pointcut = "conversationResolved()", returning = "conversation")
	public void stampSession(Conversation conversation) {
		if (conversation == null) {
			return;
		}
		// Published for IdentityObservationFilter, so the session reaches child
		// observations -- the generations and retrievals -- and not only the root.
		TraceIdentity.setConversation(conversation.getId());
		Observation current = observations.getCurrentObservation();
		if (current != null) {
			RequestTrace.session(current, conversation.getId());
			if (conversation.getKind() != null) {
				RequestTrace.metadata(current, "conversationKind", conversation.getKind().name());
			}
		}
	}

	// ---- retrieval span -----------------------------------------------------

	/**
	 * Typed {@code retriever} rather than left as a generic span, so Langfuse
	 * treats it as a retrieval step: what was searched for, and what came back.
	 * Advising the client rather than its caller means any future caller is
	 * covered too.
	 */
	@Around("knowledgeBaseRetrieval()")
	public Object traceRetrieval(ProceedingJoinPoint joinPoint) throws Throwable {
		Object[] args = joinPoint.getArgs();
		Observation observation = Observation.createNotStarted("retrieve-context", observations);
		RequestTrace.type(observation, LangfuseAttributes.TYPE_RETRIEVER);
		// retrieve(knowledgeBaseId, queryText, numberOfResults, filter, rerankerModelArn)
		RequestTrace.input(observation, args.length > 1 ? String.valueOf(args[1]) : null);
		if (args.length > 2) {
			RequestTrace.metadata(observation, "topK", String.valueOf(args[2]));
		}
		if (args.length > 4) {
			RequestTrace.metadata(observation, "reranked", String.valueOf(args[4] != null));
		}
		observation.start();
		try (Observation.Scope ignored = observation.openScope()) {
			Object result = joinPoint.proceed();
			if (result instanceof List<?> hits) {
				RequestTrace.output(observation, describeHits(hits));
				RequestTrace.metadata(observation, "hitCount", String.valueOf(hits.size()));
			}
			return result;
		}
		catch (Throwable failure) {
			observation.error(failure);
			throw failure;
		}
		finally {
			observation.stop();
		}
	}

	// ---- helpers ------------------------------------------------------------

	private static void describeRagAnswer(Observation observation, RagQueryRequest request,
			RagQueryResponse response) {
		RequestTrace.output(observation, response.answer());
		RequestTrace.metadata(observation, "grounded", String.valueOf(response.grounded()));
		RequestTrace.metadata(observation, "citationCount", String.valueOf(response.citations().size()));
		RequestTrace.metadata(observation, "profile", response.profileName());
		RequestTrace.metadata(observation, "profileVersion", String.valueOf(response.profileVersionNo()));
		if (request != null && response.retrievalQuery() != null) {
			// RagQueryService quietly falls back to the original question when a
			// rewrite comes back blank or runs away into prose. That fallback is
			// invisible in the answer, and is exactly what a trace should surface.
			RequestTrace.metadata(observation, "rewriteUsed",
					String.valueOf(!response.retrievalQuery().equals(request.question())));
		}
	}

	/** Chat requests are records with a {@code message()} component. */
	private static String message(ProceedingJoinPoint joinPoint) {
		for (Object arg : joinPoint.getArgs()) {
			if (arg instanceof ChatRequest request) {
				return request.message();
			}
		}
		return null;
	}

	private static <T> T argument(ProceedingJoinPoint joinPoint, Class<T> type) {
		for (Object arg : joinPoint.getArgs()) {
			if (type.isInstance(arg)) {
				return type.cast(arg);
			}
		}
		return null;
	}

	/**
	 * Hits as a compact JSON array. Score and a snippet answer "why did it
	 * retrieve this"; the full chunk text reappears in the generation's prompt
	 * anyway, so repeating it here would only bloat the trace.
	 */
	private static String describeHits(List<?> hits) {
		StringBuilder json = new StringBuilder("[");
		for (int i = 0; i < hits.size(); i++) {
			if (!(hits.get(i) instanceof KnowledgeBaseRetrievalResult hit)) {
				continue;
			}
			if (i > 0) {
				json.append(',');
			}
			String text = hit.content() == null ? "" : hit.content().text();
			json.append("{\"score\":").append(hit.score())
					.append(",\"text\":\"").append(escape(abbreviate(text))).append("\"}");
		}
		return json.append(']').toString();
	}

	private static String abbreviate(String text) {
		if (text == null) {
			return "";
		}
		return text.length() <= SNIPPET_CHARS ? text : text.substring(0, SNIPPET_CHARS) + "...";
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"")
				.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
	}
}
