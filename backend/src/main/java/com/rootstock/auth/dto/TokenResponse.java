package com.rootstock.auth.dto;

/**
 * Cognito's tokens, passed through untouched. {@code idToken} is the one this
 * API accepts as a bearer credential -- it is the only one carrying the
 * {@code custom:tenant_id} and {@code custom:role} claims the backend needs.
 *
 * @param refreshToken absent when refreshing, since Cognito reuses the existing one
 * @param expiresInSeconds lifetime of {@code idToken}, for the client to schedule a refresh
 */
public record TokenResponse(String idToken, String refreshToken, int expiresInSeconds) {
}
