package saasquatch.extintegration;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.text.ParseException;
import java.util.Base64;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.util.EntityUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;

public class EISquatchAuth {

	private final Executor executor;
	private final LoadingCache<Object, JWKSet> squatchJwksCache;
	private final LoadingCache<Object, String> accessTokenCache;

	private final EIIOBundle ioBundle;
	private final boolean https;
	private final String appDomain;
	private final String clientId;
	private final String clientSecret;
	private final String jwtAudience;
	private final String jwtTokenUrl;

	public EISquatchAuth(EIIOBundle ioBundle, boolean https, String appDomain,
			String clientId, String clientSecret, String jwtAudience, String jwtTokenUrl) {
		this.ioBundle = ioBundle;
		this.https = https;
		this.appDomain = appDomain;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.jwtAudience = jwtAudience;
		this.jwtTokenUrl = jwtTokenUrl;

		this.executor = ioBundle.getExecutor();
		this.squatchJwksCache = Caffeine.newBuilder()
				.refreshAfterWrite(1, TimeUnit.DAYS)
				.executor(this.executor)
				.build(ignored -> loadSquatchJwks());
		this.accessTokenCache = Caffeine.newBuilder()
				.refreshAfterWrite(6, TimeUnit.HOURS)
				.executor(this.executor)
				.build(ignored -> loadAccessToken());
	}

	public void init() {
		Stream.of(squatchJwksCache, accessTokenCache)
				.forEach(cache -> cache.get(ObjectUtils.NULL));
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
	 * @see EIAuth#getAccessKey(JWKSet, String, String)
	 */
	public Pair<Boolean, String> getIntegrationAccessKey(String jwtIssuer, String tenantScopedToken) {
		return EIAuth.getAccessKey(getCachedSquatchJwks(), getClientSecret(), jwtIssuer, tenantScopedToken);
	}

	/**
	 * @see EIAuth#verifyAccessKey(String, String)
	 */
	public String verifyIntegrationAccessKey(String accessKey) {
		return EIAuth.verifyAccessKey(getClientSecret(), accessKey);
	}

	/**
	 * Validate a SaaSquatch webhook
	 * @return optional error message
	 */
	@Nullable
	public String validateSquatchWebhook(String sigHeader, byte[] bodyBytes) {
		if (StringUtils.isBlank(sigHeader)) return "signature missing";
		final String jwtStr = StringUtils.replaceOnce(sigHeader, "..",
				'.' + Base64.getUrlEncoder().withoutPadding().encodeToString(bodyBytes) + '.');
		final SignedJWT signedJWT;
		try {
			signedJWT = SignedJWT.parse(jwtStr);
		} catch (ParseException e) {
			return "Invalid JWT";
		}
		final RSAKey jwk = (RSAKey) getCachedSquatchJwks()
				.getKeyByKeyId(signedJWT.getHeader().getKeyID());
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

	public JWKSet loadSquatchJwks() {
		final String protocol = https ? "https://" : "http://";
		try {
			return JWKSet.load(new URL(protocol + getAppDomain()
					+ "/.well-known/jwks.json"), 2500, 5000, 0);
		} catch (IOException | ParseException e) {
			throw new RuntimeException();
		}
	}

	public JWKSet getCachedSquatchJwks() {
		return squatchJwksCache.get(ObjectUtils.NULL);
	}

	public String loadAccessToken() {
		final JsonNode bodyJson = JsonNodeFactory.instance.objectNode()
				.put("client_id", getClientId())
				.put("client_secret", getClientSecret())
				.put("audience", jwtAudience)
				.put("grant_type", "client_credentials");
		try {
			final HttpPost request = new HttpPost(jwtTokenUrl);
			request.setConfig(RequestConfig.custom()
					.setConnectTimeout(2500)
					.setSocketTimeout(5000)
					.build());
			request.setEntity(new ByteArrayEntity(EIJson.mapper().writeValueAsBytes(bodyJson),
					ContentType.APPLICATION_JSON));
			try (CloseableHttpResponse resp = ioBundle.getHttpClient().execute(request)) {
				final int status = resp.getStatusLine().getStatusCode();
				final String respBody = EntityUtils.toString(resp.getEntity(), UTF_8);
				if (status >= 300) {
					throw new IllegalStateException(String.format(
							"status[%s] received from [%s]. Response body: %s",
							status, jwtTokenUrl, respBody));
				}
				final JsonNode respJson = EIJson.mapper().readTree(respBody);
				final String accessToken = respJson.path("access_token").textValue();
				if (StringUtils.isBlank(accessToken)) {
					throw new RuntimeException("access_token is blank");
				}
				return accessToken;
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public String getCachedAccessToken() {
		return accessTokenCache.get(ObjectUtils.NULL);
	}

}
