package com.rootstock.core.graph.nodes;

import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.NodeFactory;
import java.text.Normalizer;
import java.util.Map;
import org.bsc.langgraph4j.action.NodeAction;

/**
 * The {@code ingest} node: tidies the submitted text so every later step sees
 * the same thing, and records the case's ids. The case and trace ids are
 * assigned when the case is submitted, before the graph starts, so the screen
 * and the trace have them from the first moment; ingest writes them into the
 * audit trail.
 *
 * <p>Tidying never changes what the text says: Unicode is normalised (NFC),
 * line endings become {@code \n}, invisible control characters and trailing
 * spaces go, and runs of blank lines shrink to one.
 */
public final class IngestNode implements NodeFactory {

	public static final String TYPE = "ingest";

	@Override
	public Kind kind() {
		return Kind.NODE;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public NodeAction<CaseState> create(NodeContext context) {
		return state -> {
			String raw = state.rawInput().orElse("");
			String text = normalise(raw);
			int words = text.isEmpty() ? 0 : text.split("\\s+").length;
			String trace = state.<String>value(CaseState.TRACE_ID).map(t -> ", trace " + t).orElse(", not traced");
			Map<String, Object> update = AuditEntry.update(context.node().id(), "Case " + state.caseId() + trace + ": "
					+ words + " words" + (text.equals(raw) ? "" : " (tidied: " + raw.length() + " → " + text.length()
							+ " characters)"));
			update.put(CaseState.RAW_INPUT, text);
			return update;
		};
	}

	static String normalise(String input) {
		String text = Normalizer.normalize(input, Normalizer.Form.NFC)
				.replace("\r\n", "\n").replace('\r', '\n')
				.replace(' ', ' ')
				// Control and zero-width characters, but not newlines or tabs.
				.replaceAll("[\\p{Cc}&&[^\\n\\t]]|[\\u200B-\\u200D\\uFEFF]", "")
				.replaceAll("[ \\t]+\\n", "\n")
				.replaceAll("\\n{3,}", "\n\n");
		return text.strip();
	}
}
