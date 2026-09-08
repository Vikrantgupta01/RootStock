package com.rootstock.rag.document.dto;

import java.util.UUID;

public record UploadResponse(UUID documentId, DocumentVersionResponse version) {
}
