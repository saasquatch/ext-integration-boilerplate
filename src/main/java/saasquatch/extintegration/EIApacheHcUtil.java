package saasquatch.extintegration;

import static java.nio.charset.StandardCharsets.UTF_8;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.DeflateInputStream;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.io.ByteStreams;
import com.google.common.net.HttpHeaders;

public class EIApacheHcUtil {
  private static final Logger logger = LoggerFactory.getLogger(EIApacheHcUtil.class);

  public static final String DEFAULT_ACCEPT_ENCODING = "gzip,deflate";

  public static CloseableHttpClient newBlockingClient() {
    final CloseableHttpClient httpClient = HttpClients.custom()
        .disableCookieManagement()
        .setMaxConnPerRoute(100) // default is 2
        .setMaxConnTotal(200) // default is 20
        .build();
    return httpClient;
  }

  public static CloseableHttpAsyncClient newAsyncClient() {
    final CloseableHttpAsyncClient httpAsyncClient = HttpAsyncClients.custom()
        .disableCookieManagement()
        .setMaxConnPerRoute(100) // default is 2
        .setMaxConnTotal(200) // default is 20
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
  public static byte[] getBodyBytes(HttpResponse resp) throws IOException {
    final String contentEncoding = Optional.ofNullable(
        resp.getFirstHeader(HttpHeaders.CONTENT_ENCODING))
        .map(NameValuePair::getValue)
        .map(StringUtils::stripToNull)
        .map(String::toLowerCase)
        .orElse(null);
    try (
      InputStream originalIn = resp.getEntity().getContent();
      InputStream wrappedIn = getInputStreamForContentEncoding(
        resp.getEntity().getContent(), contentEncoding);
    ) {
      return ByteStreams.toByteArray(wrappedIn);
    }
  }

  /**
   * Get response body as a String based on the content encoding and charset.
   * This method is meant to be used with the async client.
   */
  public static String getBodyText(HttpResponse resp) throws IOException {
    ContentType contentType = null;
    try {
      contentType = ContentType.get(resp.getEntity());
    } catch (RuntimeException e) {
      // ignore
    }
    final Charset charset = Optional.ofNullable(contentType)
        .map(ContentType::getCharset)
        .orElse(UTF_8);
    return new String(getBodyBytes(resp), charset);
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
