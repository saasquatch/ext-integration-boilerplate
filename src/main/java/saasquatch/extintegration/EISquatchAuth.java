package saasquatch.extintegration;

import static java.nio.charset.StandardCharsets.UTF_8;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequests;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.net.HttpHeaders;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.saasquatch.common.base.RSUrlCodec;

public class EISquatchAuth {

  private final Executor executor;
  // kid -> JWK
  private final AsyncLoadingCache<String, JWK> squatchJwkCache;
  private final LoadingCache<Object, String> accessTokenCache;
  // tenantAlias -> segment integration
  private final AsyncLoadingCache<String, JsonNode> integrationInstanceCache;

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
    this.squatchJwkCache = Caffeine.newBuilder()
        .refreshAfterWrite(1, TimeUnit.DAYS)
        .maximumSize(8)
        .executor(this.executor)
        .<String, JWK>buildAsync((kid, _executor) -> {
          return loadJwkForSquatchJwks(kid).toCompletableFuture();
        });
    this.accessTokenCache = Caffeine.newBuilder()
        .refreshAfterWrite(6, TimeUnit.HOURS)
        .executor(this.executor)
        .build(ignored -> loadAccessToken());
    this.integrationInstanceCache =
        Caffeine.newBuilder()
        .maximumSize(16)
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .executor(this.executor)
        .buildAsync((tenantAlias, _executor) -> {
          return loadIntegration(tenantAlias).toCompletableFuture();
        });
  }

  public void init() {
    Stream.of(accessTokenCache)
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
   * @see EIAuth#verifyTenantScopedToken(JWKSet, String, String)
   */
  public Pair<Boolean, String> verifyTenantScopedToken(String tenantScopedToken,
      String integrationName) {
    return EIAuth.verifyTenantScopedToken(this::getCachedJwkForKid, integrationName,
        tenantScopedToken);
  }

  /**
   * @see EIAuth#getAccessKey(JWKSet, String, String)
   */
  public Pair<Boolean, String> getIntegrationAccessKey(String jwtIssuer, String integrationName,
      String tenantScopedToken) {
    return EIAuth.getAccessKey(this::getCachedJwkForKid, integrationName, getClientSecret(),
        jwtIssuer, tenantScopedToken);
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
    final RSAKey jwk = (RSAKey) getCachedJwkForKid(signedJWT.getHeader().getKeyID());
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

  public CompletionStage<JWK> loadJwkForSquatchJwks(String kid) {
    final String protocol = https ? "https://" : "http://";
    final SimpleHttpRequest request = SimpleHttpRequests.get(protocol + getAppDomain()
        + "/.well-known/jwks.json");
    request.setConfig(RequestConfig.custom()
        .setConnectTimeout(2500, TimeUnit.MILLISECONDS)
        .setResponseTimeout(5, TimeUnit.SECONDS)
        .build());
    final CompletableFuture<SimpleHttpResponse> cf = new CompletableFuture<>();
    ioBundle.getHttpAsyncClient().execute(request, EIApacheHcUtil.completableFuture(cf));
    return cf.thenApplyAsync(resp -> {
      final JWKSet jwks;
      try {
        jwks = JWKSet.parse(resp.getBodyText());
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
      return jwks.getKeyByKeyId(kid);
    }, executor);
  }

  public JWK getCachedJwkForKid(String kid) {
    return squatchJwkCache.synchronous().get(kid);
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
          .setConnectTimeout(3, TimeUnit.SECONDS)
          .setResponseTimeout(5, TimeUnit.SECONDS)
          .build());
      request.setEntity(new ByteArrayEntity(EIJson.mapper().writeValueAsBytes(bodyJson),
          ContentType.APPLICATION_JSON));
      try (CloseableHttpResponse resp = ioBundle.getHttpClient().execute(request)) {
        final int status = resp.getCode();
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
      } catch (org.apache.hc.core5.http.ParseException e) {
        throw new RuntimeException(e);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public String getCachedAccessToken() {
    return accessTokenCache.get(ObjectUtils.NULL);
  }

  public String getAuthHeader() {
    return "Bearer " + getCachedAccessToken();
  }

  public CompletionStage<JsonNode> loadIntegration(String tenantAlias) {
    final String url = String.format("https://%s/api/v1/%s/integration/%s",
        getAppDomain(), tenantAlias, RSUrlCodec.encode(getClientId()));
    final SimpleHttpRequest request = SimpleHttpRequests.get(url);
    request.setHeader(HttpHeaders.ACCEPT_ENCODING, EIApacheHcUtil.DEFAULT_ACCEPT_ENCODING);
    request.setHeader(HttpHeaders.AUTHORIZATION, getAuthHeader());
    final CompletableFuture<SimpleHttpResponse> respPromise = new CompletableFuture<>();
    ioBundle.getHttpAsyncClient().execute(request,
        EIApacheHcUtil.completableFuture(respPromise));
    return respPromise.thenApplyAsync(resp -> {
      final JsonNode respJson;
      try {
        final int status = resp.getCode();
        if (status < 300) {
          respJson = EIJson.mapper().readTree(EIApacheHcUtil.getBodyBytes(resp));
        } else if (status == HttpStatus.SC_NOT_FOUND) {
          respJson = null;
        } else {
          final String respBody = EIApacheHcUtil.getBodyText(resp);
          throw new RuntimeException(String.format("status[%s] received from [%s]. Response: %s",
              status, request.getUri(), respBody));
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } catch (URISyntaxException e) {
        throw new RuntimeException(e);
      }
      return respJson;
    }, executor);
  }

  public CompletionStage<JsonNode> getCachedIntegration(String tenantAlias) {
    return integrationInstanceCache.get(tenantAlias);
  }

  public void clearIntegrationCache(String tenantAlias) {
    integrationInstanceCache.synchronous().invalidate(tenantAlias);
  }

  public CompletionStage<JsonNode> getCachedIntegrationConfig(String tenantAlias) {
    return getCachedIntegration(tenantAlias)
        .thenApplyAsync(integration -> {
          if (integration == null)
            return null;
          if (!integration.path("enabled").asBoolean(false))
            return null;
          return integration.get("config");
        }, executor).thenApplyAsync(integration -> {
          return Optional.ofNullable(integration)
              .orElseGet(JsonNodeFactory.instance::objectNode);
        }, executor);
  }

  public CompletionStage<JsonNode> updateIntegrationConfig(String tenantAlias,
      JsonNode integrationConfig) {
    return loadIntegration(tenantAlias)
        .thenApplyAsync(ObjectNode.class::cast, executor)
        .thenComposeAsync(integration -> {
          if (integration == null) {
            throw new IllegalStateException(
                String.format("Tenant[%s] does not have an integration", tenantAlias));
          }
          final JsonNode originalConfig = Optional.ofNullable(integration.get("config"))
              .orElseGet(JsonNodeFactory.instance::objectNode);
          final JsonNode updatedConfig;
          try {
            updatedConfig = EIJson.mapper().updateValue(originalConfig, integrationConfig);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          integration.set("config", updatedConfig);
          final SimpleHttpRequest putReq = SimpleHttpRequests.put(String.format(
              "https://%s/api/v1/%s/integration",  getAppDomain(), tenantAlias));
          putReq.setHeader(HttpHeaders.ACCEPT_ENCODING, EIApacheHcUtil.DEFAULT_ACCEPT_ENCODING);
          putReq.setHeader(HttpHeaders.AUTHORIZATION, getAuthHeader());
          try {
            putReq.setBody(EIJson.mapper().writeValueAsBytes(integration),
                ContentType.APPLICATION_JSON);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          final CompletableFuture<SimpleHttpResponse> respPromise = new CompletableFuture<>();
          ioBundle.getHttpAsyncClient().execute(putReq,
              EIApacheHcUtil.completableFuture(respPromise));
          return respPromise;
        }, executor)
        .thenApplyAsync(resp -> {
          final int status = resp.getCode();
          if (status > 299) {
            final String respBody;
            try {
              respBody = EIApacheHcUtil.getBodyText(resp);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
            throw new RuntimeException(String.format(
                "status[%s] received when updating integration. Response: %s",
                status, respBody));
          }
          try {
            clearIntegrationCache(tenantAlias);
            return EIJson.mapper().readTree(EIApacheHcUtil.getBodyBytes(resp));
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }, executor);
  }

  public CompletionStage<EIGraphQLResponse> graphQL(String tenantAlias, String query,
      String operationName, JsonNode variables) {
    final ObjectNode reqJson = JsonNodeFactory.instance.objectNode();
    reqJson.put("query", Validate.notBlank(query));
    if (operationName != null) {
      reqJson.put("operationName", operationName);
    }
    if (EIJson.nonEmpty(variables)) {
      reqJson.set("variables", variables);
    }
    final SimpleHttpRequest gqlReq = SimpleHttpRequests.post(String.format(
        "https://%s/api/v1/%s/graphql", getAppDomain(), tenantAlias));
    gqlReq.setHeader(HttpHeaders.ACCEPT_ENCODING, EIApacheHcUtil.DEFAULT_ACCEPT_ENCODING);
    gqlReq.setHeader(HttpHeaders.AUTHORIZATION, getAuthHeader());
    try {
      gqlReq.setBody(EIJson.mapper().writeValueAsBytes(reqJson), ContentType.APPLICATION_JSON);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    final CompletableFuture<SimpleHttpResponse> respPromise = new CompletableFuture<>();
    ioBundle.getHttpAsyncClient().execute(gqlReq, EIApacheHcUtil.completableFuture(respPromise));
    return respPromise.thenApplyAsync(resp -> {
      final int status = resp.getCode();
      if (status > 299) {
        String bodyText = "";
        try {
          bodyText = EIApacheHcUtil.getBodyText(resp);
        } catch (IOException e) {}
        throw new IllegalStateException(
            String.format("Status[%s] received for GraphQL request for tenant[%s]. Body: %s",
                status, tenantAlias, bodyText));
      }
      try {
        return EIJson.mapper().readValue(EIApacheHcUtil.getBodyBytes(resp),
            EIGraphQLResponse.class);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }, executor);
  }

}
