package saasquatch.extintegration;

import java.io.Closeable;
import java.util.concurrent.Executor;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EIIOBundle implements Closeable {
  private static final Logger logger = LoggerFactory.getLogger(EIIOBundle.class);

  private final CloseableHttpClient httpClient;
  private final CloseableHttpAsyncClient httpAsyncClient;
  private final Executor executor;

  public EIIOBundle(Executor executor) {
    this.executor = executor;
    this.httpClient = EIApacheHcUtil.newBlockingClient();
    this.httpAsyncClient = EIApacheHcUtil.newAsyncClient();
  }

  public void start() {
    httpAsyncClient.start();
  }

  @Override
  public void close() {
    try (
      AutoCloseable c1 = httpClient;
      AutoCloseable c2 = httpAsyncClient;
    ) {
      // Do nothing
    } catch (Exception e) {
      logger.warn("Exception encountered in close()", e);
    }
  }

  public Executor getExecutor() {
    return executor;
  }

  public CloseableHttpClient getHttpClient() {
    return httpClient;
  }

  public CloseableHttpAsyncClient getHttpAsyncClient() {
    return httpAsyncClient;
  }

}
