package com.rootstock.rag.document;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rootstock.common.ResourceNotFoundException;
import com.rootstock.common.UnsupportedContentTypeException;
import com.rootstock.rag.document.dto.DocumentVersionResponse;
import com.rootstock.rag.document.dto.UploadResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.rootstock.common.GlobalExceptionHandler;

// Security is deliberately out of this slice: the filter chain lives in
// SecurityConfig (not loaded by @WebMvcTest) and @PreAuthorize sits on the
// services this test mocks out. Authentication and role enforcement are
// covered end to end by the RAG integration tests instead.
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(DocumentController.class)
@Import(GlobalExceptionHandler.class)
class DocumentControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	DocumentService service;

	private static UploadResponse sampleUpload(UUID docId) {
		DocumentVersionResponse v = new DocumentVersionResponse(
				UUID.randomUUID(), 1, DocumentStatus.PENDING, 42L, "hash", 0, true, null,
				Instant.parse("2026-01-01T00:00:00Z"), null);
		return new UploadResponse(docId, v);
	}

	@Test
	void uploadReturns201() throws Exception {
		UUID docId = UUID.randomUUID();
		given(service.upload(any(), any(), any())).willReturn(sampleUpload(docId));

		MockMultipartFile file = new MockMultipartFile("file", "guide.txt", "text/plain", "hello".getBytes());

		mockMvc.perform(multipart("/api/rag/documents").file(file).header("X-Tenant-Id", "acme"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.documentId").value(docId.toString()))
				.andExpect(jsonPath("$.version.versionNo").value(1));
	}

	@Test
	void unsupportedTypeReturns415() throws Exception {
		given(service.upload(any(), any(), any()))
				.willThrow(new UnsupportedContentTypeException("Unsupported content type: image/png"));

		MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", new byte[] {1, 2, 3});

		mockMvc.perform(multipart("/api/rag/documents").file(file).header("X-Tenant-Id", "acme"))
				.andExpect(status().isUnsupportedMediaType());
	}

	@Test
	void getMissingDocumentReturns404() throws Exception {
		UUID id = UUID.randomUUID();
		given(service.get(eq(id))).willThrow(ResourceNotFoundException.of("Document", id));

		mockMvc.perform(get("/api/rag/documents/{id}", id))
				.andExpect(status().isNotFound());
	}

	@Test
	void deleteReturns204() throws Exception {
		UUID id = UUID.randomUUID();
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.delete("/api/rag/documents/{id}", id))
				.andExpect(status().isNoContent());
		verify(service).deleteDocument(id);
	}
}
