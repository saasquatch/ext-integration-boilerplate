package saasquatch.extintegration;

import java.io.IOException;
import java.text.ParseException;
import java.time.Instant;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.KeyLengthException;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

public class EIAuth {

  /**
   * Verify a tenant scoped token
   * @return Pair of (verified, tenantAlias)
   */
  public static Pair<Boolean, String> verifyTenantScopedToken(JWKSet squatchJwks,
      String integrationName, String tenantScopedToken) {
    final SignedJWT squatchJwt;
    try {
      squatchJwt = SignedJWT.parse(tenantScopedToken);
    } catch (ParseException e) {
      return Pair.of(false, "Invalid JWT");
    }
    final RSAKey jwk = (RSAKey) squatchJwks.getKeyByKeyId(squatchJwt.getHeader().getKeyID());
    if (jwk == null) {
      return Pair.of(false, "jwk not found for kid");
    }
    final boolean verifyResult;
    try {
      final JWSVerifier verifier = new RSASSAVerifier(jwk);
      verifyResult = squatchJwt.verify(verifier);
    } catch (JOSEException e) {
      throw new RuntimeException(e);
    }
    if (!verifyResult) {
      return Pair.of(false, "Invalid JWT signature");
    }
    final JsonNode payloadJson;
    try {
      payloadJson = EIJson.mapper().readTree(squatchJwt.getPayload().toBytes());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    final String integration = payloadJson.path("integration").asText("");
    if (!integration.equalsIgnoreCase(integrationName)) {
      return Pair.of(false, "Invalid integration");
    }
    final String tenantAlias = StringUtils.substringBeforeLast(
        payloadJson.path("sub").asText(""), "@tenants");
    if (StringUtils.isBlank(tenantAlias)) {
      return Pair.of(false, "Blank tenantAlias");
    }
    final JsonNode jwtExp = payloadJson.path("exp");
    if (jwtExp.canConvertToLong()
        && Instant.ofEpochSecond(jwtExp.longValue()).isBefore(Instant.now())) {
      return Pair.of(false, "JWT expired");
    }
    return Pair.of(true, tenantAlias);
  }

  /**
   * Take a token generated by SaaSquatch and generate a new token that will be passed back
   * to the integration.
   * @return Pair of (false, errorMessage) or (true, signedJwt)
   */
  public static Pair<Boolean, String> getAccessKey(JWKSet squatchJwks, String integrationName,
      String clientSecret, String jwtIssuer, String tenantScopedToken) {
    final Pair<Boolean, String> verifyTenantScopedToken =
        verifyTenantScopedToken(squatchJwks, integrationName, tenantScopedToken);
    if (!verifyTenantScopedToken.getLeft()) {
      return verifyTenantScopedToken;
    }
    final String tenantAlias = verifyTenantScopedToken.getRight();
    final JWSSigner signer;
    try {
      signer = new MACSigner(clientSecret);
    } catch (KeyLengthException e) {
      throw new RuntimeException(e);
    }
    final SignedJWT segmentJwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.HS256)
        .type(JOSEObjectType.JWT)
        .build(),
        new JWTClaimsSet.Builder()
        .issuer(jwtIssuer)
        .claim("sub", tenantAlias + "@tenants")
        .build());
    try {
      segmentJwt.sign(signer);
    } catch (JOSEException e) {
      throw new RuntimeException(e);
    }
    return Pair.of(true, segmentJwt.serialize());
  }

  /**
   * Verify an access key coming from the 3rd party service
   * @return optional error message
   */
  @Nullable
  public static String verifyAccessKey(String clientSecret, @Nullable String accessKey) {
    final SignedJWT signedJWT;
    try {
      signedJWT = SignedJWT.parse(accessKey);
    } catch (ParseException e) {
      return "Invalid JWT";
    }
    final boolean verifyResult;
    try {
      final JWSVerifier verifier = new MACVerifier(clientSecret);
      verifyResult = signedJWT.verify(verifier);
    } catch (JOSEException e) {
      throw new RuntimeException(e);
    }
    if (!verifyResult) {
      return "Invalid JWT signature";
    }
    return null;
  }

}
