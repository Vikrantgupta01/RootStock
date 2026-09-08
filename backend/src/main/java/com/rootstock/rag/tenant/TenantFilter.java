package com.rootstock.rag.tenant;

import com.rootstock.rag.RagProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds a tenant id to the request thread from the configured header
 * ({@code rootstock.rag.tenant.header}, default {@code X-Tenant-Id}), falling
 * back to {@code rootstock.rag.tenant.default-tenant}. Stub for real auth.
 *
 * <p>Registered as a bean by {@link com.rootstock.rag.RagConfig} rather than a
 * {@code @Component} so web-slice tests ({@code @WebMvcTest}) don't try to load
 * it without the RAG properties.
 */
public class TenantFilter extends OncePerRequestFilter {

	private final RagProperties.Tenant properties;

	public TenantFilter(RagProperties properties) {
		this.properties = properties.tenant();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(properties.header());
		String tenantId = StringUtils.hasText(header) ? header.trim() : properties.defaultTenant();
		TenantContext.set(tenantId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			TenantContext.clear();
		}
	}
}
