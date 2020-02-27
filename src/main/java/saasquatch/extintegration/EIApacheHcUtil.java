package saasquatch.extintegration;

import static java.nio.charset.StandardCharsets.UTF_8;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.DeflateInputStream;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.NameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.io.ByteStreams;
import com.google.common.net.HttpHeaders;

public class EIApacheHcUtil {
  private static final Logger logger = LoggerFactory.getLogger(EIApacheHcUtil.class);

  public static final String DEFAULT_ACCEPT_ENCODING = "gzip,deflate";

  private static final RequestConfig defaultRequestConfig = RequestConfig.custom()
      // Set some generous timeouts just to prevent a hanging connection
      .setConnectTimeout(30, TimeUnit.SECONDS)
      .setConnectionRequestTimeout(60, TimeUnit.SECONDS)
      .setResponseTimeout(60, TimeUnit.SECONDS)
      .build();

  public static CloseableHttpClient newBlockingClient() {
    final CloseableHttpClient httpClient = HttpClients.custom()
        .disableCookieManagement()
        .setDefaultRequestConfig(defaultRequestConfig)
        .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnPerRoute(100) // default is 2
            .setMaxConnTotal(200) // default is 20
            .build())
        .build();
    return httpClient;
  }

  public static CloseableHttpAsyncClient newAsyncClient() {
    final CloseableHttpAsyncClient httpAsyncClient = HttpAsyncClients.custom()
        .disableCookieManagement()
        .setDefaultRequestConfig(defaultRequestConfig)
        .setConnectionManager(PoolingAsyncClientConnectionManagerBuilder.create()
            .setMaxConnPerRoute(100) // default is 2
            .setMaxConnTotal(200) // default is 20
            .build())
        .build();
    return httpAsyncClient;
  }

  /**
   * @return a {@link FutureCallback} that will populate the given {@link CompletableFuture}.
   */
  public static <T> FutureCallback<T> completableFuture(CompletableFuture<T> cf) {
    return new FutureCallback<T>() {

      @Override
      public void completed(T result) {
        cf.complete(result);
      }

      @Override
      public void failed(Exception ex) {
        cf.completeExceptionally(ex);
      }

      @Override
      public void cancelled() {
        cf.cancel(true);
      }

    };
  }

  /**
   * Get response body as a byte array based on the content encoding.
   * This method is meant to be used with the async client.
   */
  public static byte[] getBodyBytes(SimpleHttpResponse resp) throws IOException {
    final String contentEncoding = Optional.ofNullable(
        resp.getFirstHeader(HttpHeaders.CONTENT_ENCODING))
        .map(NameValuePair::getValue)
        .map(StringUtils::stripToNull)
        .map(String::toLowerCase)
        .orElse(null);
    final byte[] bodyBytes = resp.getBodyBytes();
    if (bodyBytes == null) return null;
    try (
      InputStream wrappedIn = getInputStreamForContentEncoding(
          new ByteArrayInputStream(bodyBytes), contentEncoding);
    ) {
      return ByteStreams.toByteArray(wrappedIn);
    }
  }

  /**
   * Get response body as a String based on the content encoding and charset.
   * This method is meant to be used with the async client.
   */
  public static String getBodyText(SimpleHttpResponse resp) throws IOException {
    final byte[] bodyBytes = getBodyBytes(resp);
    if (bodyBytes == null) return null;
    final Charset charset = Optional.ofNullable(resp.getContentType())
        .map(ContentType::getCharset)
        .orElse(UTF_8);
    return new String(bodyBytes, charset);
  }

  static InputStream getInputStreamForContentEncoding(InputStream source,
      @Nullable String contentEncoding) throws IOException {
    if (StringUtils.isBlank(contentEncoding)) return source;
    switch (contentEncoding.trim().toLowerCase()) {
    case "gzip":
    case "x-gzip":
      return new GZIPInputStream(source);
    case "deflate":
      return new DeflateInputStream(source);
    case "identity":
      return source;
    default:
      logger.warn("Unrecognized Content-Encoding: [{}]", contentEncoding);
      return source;
    }
  }

}
