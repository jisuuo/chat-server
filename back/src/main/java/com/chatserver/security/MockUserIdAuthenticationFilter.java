package com.chatserver.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Temporary authentication: trusts the X-User-Id header as the caller's
 * identity. Replace with real credential verification once the auth
 * system exists; downstream code only depends on java.security.Principal.
 */
public class MockUserIdAuthenticationFilter extends OncePerRequestFilter {

	public static final String USER_ID_HEADER = "X-User-Id";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String userId = request.getHeader(USER_ID_HEADER);
		if (userId != null && !userId.isBlank()) {
			Authentication authentication = new UsernamePasswordAuthenticationToken(userId, null, List.of());
			SecurityContextHolder.getContext().setAuthentication(authentication);
		}
		filterChain.doFilter(request, response);
	}
}
