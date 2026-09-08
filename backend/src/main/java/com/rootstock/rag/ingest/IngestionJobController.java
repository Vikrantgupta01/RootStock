package com.rootstock.rag.ingest;

import com.rootstock.common.ResourceNotFoundException;
import com.rootstock.rag.tenant.TenantContext;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of the ingestion ledger for the Activity tab. */
@RestController
@RequestMapping("/api/rag/jobs")
public class IngestionJobController {

	private final IngestionJobRepository jobs;

	public IngestionJobController(IngestionJobRepository jobs) {
		this.jobs = jobs;
	}

	@GetMapping
	public PagedModel<IngestionJobResponse> list(
			@RequestParam(value = "state", required = false) IngestionJobState state,
			@PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
		String tenantId = TenantContext.require();
		Page<IngestionJob> page = (state == null)
				? jobs.findByTenantId(tenantId, pageable)
				: jobs.findByTenantIdAndState(tenantId, state, pageable);
		return new PagedModel<>(page.map(IngestionJobResponse::of));
	}

	@GetMapping("/{id}")
	public IngestionJobResponse get(@PathVariable UUID id) {
		return jobs.findByTenantIdAndId(TenantContext.require(), id)
				.map(IngestionJobResponse::of)
				.orElseThrow(() -> ResourceNotFoundException.of("Ingestion job", id));
	}
}
