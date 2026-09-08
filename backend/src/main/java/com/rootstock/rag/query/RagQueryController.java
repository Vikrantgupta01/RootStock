package com.rootstock.rag.query;

import com.rootstock.rag.query.dto.RagQueryRequest;
import com.rootstock.rag.query.dto.RagQueryResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Retrieval-augmented question answering over a tenant's indexed documents. */
@RestController
@RequestMapping("/api/rag/query")
public class RagQueryController {

	private final RagQueryService service;

	public RagQueryController(RagQueryService service) {
		this.service = service;
	}

	@PostMapping
	public RagQueryResponse query(@Valid @RequestBody RagQueryRequest request) {
		return service.query(request);
	}
}
