package saasquatch.extintegration;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.util.Base64;

import javax.annotation.Nullable;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.io.ByteStreams;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;

public class EISquatchAuth {

	private final boolean https;
	private final String appDomain;
	private final String clientId;
	private final String clientSecret;
	private final String jwtAudience;
	private final String jwtTokenUrl;

	public EISquatchAuth(boolean https, String appDomain, String clientId, String clientSecret,
			String jwtAudience, String jwtTokenUrl) {
		this.https = https;
		this.appDomain = appDomain;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.jwtAudience = jwtAudience;
		this.jwtTokenUrl = jwtTokenUrl;
	}

	public String getAppDomain() {
		return appDomain;
	}

	public String getClientId() {
		return clientId;
	}

	public String getClientSecret() {
		return clientSecret;
	}

	/**
	 * Validate a SaaSquatch webhook
	 * @return optional error message
	 */
	@Nullable
	public String validateSquatchWebhook(JWKSet squatchJwks, String sigHeader, byte[] bodyBytes) {
		if (StringUtils.isBlank(sigHeader)) return "signature missing";
		final String jwtStr = StringUtils.replaceOnce(sigHeader, "..",
				'.' + Base64.getUrlEncoder().withoutPadding().encodeToString(bodyBytes) + '.');
		final SignedJWT signedJWT;
		try {
			signedJWT = SignedJWT.parse(jwtStr);
		} catch (ParseException e) {
			return "Invalid JWT";
		}
		final RSAKey jwk = (RSAKey) squatchJwks.getKeyByKeyId(signedJWT.getHeader().getKeyID());
		if (jwk == null) {
			return "jwk not found for kid";
		}
		final JWSVerifier verifier;
		try {
			verifier = new RSASSAVerifier(jwk);
		} catch (JOSEException e) {
			throw new RuntimeException(e); // internal error
		}
		final boolean verifyResult;
		try {
			verifyResult = signedJWT.verify(verifier);
		} catch (JOSEException e) {
			throw new RuntimeException(e); // internal error
		}
		if (!verifyResult) {
			return "Invalid JWT signature";
		}
		return null;
	}

	public JWKSet loadSaaSquatchJwks() {
		final String protocol = https ? "https://" : "http://";
		try {
			return JWKSet.load(new URL(protocol + getAppDomain()
					+ "/.well-known/jwks.json"), 2500, 5000, 0);
		} catch (IOException | ParseException e) {
			throw new RuntimeException();
		}
	}

	public String loadAccessToken() {
		final JsonNode bodyJson = JsonNodeFactory.instance.objectNode()
				.put("client_id", getClientId())
				.put("client_secret", getClientSecret())
				.put("audience", jwtAudience)
				.put("grant_type", "client_credentials");
		try {
			final HttpURLConnection conn = (HttpURLConnection) new URL(jwtTokenUrl).openConnection();
			conn.setInstanceFollowRedirects(true);
			conn.setConnectTimeout(2500);
			conn.setReadTimeout(5000);
			conn.setRequestMethod("POST");
			conn.setRequestProperty(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString());
			conn.setDoOutput(true);
			try (OutputStream connOut = conn.getOutputStream()) {
				connOut.write(EIJson.mapper().writeValueAsBytes(bodyJson));
			}
			final int status = conn.getResponseCode();
			if (status >= 300) {
				final String respBody;
				try (InputStream connErr = conn.getErrorStream()) {
					if (connErr == null) {
						respBody = null;
					} else {
						respBody = new String(ByteStreams.toByteArray(connErr), UTF_8);
					}
				}
				throw new IllegalStateException(String.format(
						"status[%s] received from [%s]. Response body: %s",
						status, jwtTokenUrl, respBody));
			}
			final JsonNode respJson;
			try (InputStream connIn = conn.getInputStream()) {
				respJson = EIJson.mapper().readTree(connIn);
			}
			final String accessToken = respJson.path("access_token").asText("");
			if (StringUtils.isBlank(accessToken)) {
				throw new RuntimeException("access_token is blank");
			}
			return accessToken;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
