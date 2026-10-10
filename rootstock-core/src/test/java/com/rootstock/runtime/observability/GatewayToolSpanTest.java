package com.rootstock.runtime.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.core.tools.ToolAccess;
import com.rootstock.core.tools.ToolCallContext;
import com.rootstock.core.tools.ToolCatalog;
import com.rootstock.core.tools.ToolDefinition;
import com.rootstock.core.tools.ToolGateway;
import com.rootstock.core.tools.ToolInvoker;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.env.MockEnvironment;

/**
 * The spans Langfuse receives for gateway calls: the real aspect around a real
 * ToolGateway, with every stopped observation recorded so its Langfuse
 * attributes can be read back.
 */
class GatewayToolSpanTest {

	private final List<Observation.Context> stopped = new ArrayList<>();
	private final ObservationRegistry registry = ObservationRegistry.create();
	private final ToolGateway gateway;

	GatewayToolSpanTest() {
		registry.observationConfig().observationHandler(new ObservationHandler<>() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

			@Override
			public void onStop(Observation.Context context) {
				stopped.add(context);
			}
		});
		ToolCatalog catalog = new ToolCatalog(
				Map.of("find_household", new ToolDefinition("find_household", "client", "find_household", ToolAccess.READ),
						"broken", new ToolDefinition("broken", "client", "broken", ToolAccess.READ)),
				Map.of("enrich", List.of("find_household", "broken")), Set.of());
		ToolInvoker invoker = (tool, arguments, context) -> {
			if (tool.name().equals("broken")) {
				throw new ToolInvoker.ToolErrorException("No household with ref HH-9999");
			}
			return "{\"matches\":[{\"householdRef\":\"HH-0001\"}]}";
		};
		AspectJProxyFactory proxy = new AspectJProxyFactory(new ToolGateway(catalog, invoker));
		proxy.setProxyTargetClass(true);
		proxy.addAspect(new TracingAspect(new RequestTrace(registry, new MockEnvironment()), registry));
		gateway = proxy.getProxy();
	}

	@Test
	void aCallInsideARequestIsAChildToolSpanWithItsCaseAndOutput() {
		Observation request = Observation.start("request", registry);
		try (Observation.Scope ignored = request.openScope()) {
			gateway.call("enrich", "find_household", Map.of("name", "Linh Tran", "suburb", "Blacktown"),
					new ToolCallContext("case-42", "volunteer-7"));
		}
		request.stop();

		Observation.Context span = stopped.get(0);
		assertThat(span.getName()).isEqualTo("tool-find_household");
		assertThat(span.getParentObservation().getContextView()).isSameAs(request.getContextView());
		assertThat(value(span, LangfuseAttributes.OBSERVATION_TYPE)).isEqualTo("tool");
		assertThat(value(span, LangfuseAttributes.OBSERVATION_INPUT)).contains("\"name\":\"Linh Tran\"");
		assertThat(value(span, LangfuseAttributes.OBSERVATION_OUTPUT)).contains("HH-0001");
		assertThat(value(span, LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "caseId")).isEqualTo("case-42");
		assertThat(value(span, LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "actingUser")).isEqualTo("volunteer-7");
		assertThat(value(span, LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "node")).isEqualTo("enrich");
		assertThat(value(span, LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "status")).isEqualTo("OK");
		assertThat(value(span, LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "connection")).isEqualTo("client");
		assertThat(value(span, LangfuseAttributes.OBSERVATION_LEVEL)).isNull();
	}

	@Test
	void aCallOnItsOwnStartsATraceWithTheCaseAsSession() {
		gateway.call("enrich", "find_household", Map.of(), new ToolCallContext("case-42", null));

		Observation.Context trace = stopped.get(0);
		assertThat(trace.getName()).isEqualTo("call-tool");
		assertThat(trace.getParentObservation()).isNull();
		assertThat(value(trace, LangfuseAttributes.TRACE_NAME)).isEqualTo("call-tool");
		assertThat(value(trace, LangfuseAttributes.SESSION_ID)).isEqualTo("case-42");
		assertThat(value(trace, LangfuseAttributes.TRACE_TAGS)).contains("tools");
	}

	@Test
	void aBlockedCallIsAWarningWithTheReason() {
		gateway.call("draft", "find_household", Map.of(), ToolCallContext.NONE);

		Observation.Context span = stopped.get(0);
		assertThat(value(span, LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "status")).isEqualTo("BLOCKED");
		assertThat(value(span, LangfuseAttributes.OBSERVATION_LEVEL)).isEqualTo("WARNING");
		assertThat(value(span, LangfuseAttributes.OBSERVATION_STATUS_MESSAGE)).contains("not allowed in node 'draft'");
	}

	@Test
	void aToolErrorIsAnErrorWithTheSystemsMessage() {
		gateway.call("enrich", "broken", Map.of(), ToolCallContext.NONE);

		Observation.Context span = stopped.get(0);
		assertThat(value(span, LangfuseAttributes.OBSERVATION_LEVEL)).isEqualTo("ERROR");
		assertThat(value(span, LangfuseAttributes.OBSERVATION_STATUS_MESSAGE)).isEqualTo("No household with ref HH-9999");
		assertThat(value(span, LangfuseAttributes.OBSERVATION_OUTPUT)).isEqualTo("No household with ref HH-9999");
	}

	private static String value(Observation.Context context, String key) {
		KeyValue kv = context.getHighCardinalityKeyValue(key);
		return kv == null ? null : kv.getValue();
	}
}
