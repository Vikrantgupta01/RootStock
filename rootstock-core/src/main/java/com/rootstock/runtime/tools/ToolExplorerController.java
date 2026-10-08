package com.rootstock.runtime.tools;

import com.rootstock.auth.AuthContext;
import com.rootstock.core.tools.ToolCallContext;
import com.rootstock.core.tools.ToolCallResult;
import com.rootstock.core.tools.ToolGateway;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Tool explorer: run any configured client-system tool as a chosen graph
 * node would, through the same {@link ToolGateway} the graph uses. A tool the
 * node may not call is refused exactly as it would be in a run, which is the
 * point: the screen shows the allowlists working, not a way around them.
 *
 * <p>Admins only: results contain the client's records.
 */
@RestController
@RequestMapping("/api/tools")
@PreAuthorize("hasRole('ADMIN')")
public class ToolExplorerController {

	private final ToolGateway gateway;
	private final ToolExplorerService explorer;

	public ToolExplorerController(ToolGateway gateway, ToolExplorerService explorer) {
		this.gateway = gateway;
		this.explorer = explorer;
	}

	/** Nodes with what each may call, and every tool as its client system describes it. */
	@GetMapping
	public ToolExplorerService.Overview overview() {
		return explorer.overview();
	}

	/**
	 * @param caseId optional: ties the call to a case in the trace (the Langfuse
	 *               session), as a graph run would
	 */
	public record CallRequest(
			@NotBlank String node,
			@NotBlank String tool,
			Map<String, Object> arguments,
			@Size(max = 100) String caseId) {
	}

	/** The gateway's result, plus where to see the call in Langfuse (null when tracing is off). */
	public record CallResponse(ToolCallResult result, String traceId, String traceUrl) {
	}

	@PostMapping("/call")
	public CallResponse call(@Valid @RequestBody CallRequest request) {
		// The acting user is whoever is signed in, never a value from the request.
		ToolCallContext context = new ToolCallContext(blankToNull(request.caseId()), AuthContext.require().userId());
		ToolCallResult result = gateway.call(request.node(), request.tool(), request.arguments(), context);
		String traceId = explorer.currentTraceId();
		return new CallResponse(result, traceId, explorer.traceUrl(traceId));
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}
}
