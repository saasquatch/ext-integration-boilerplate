package saasquatch.extintegration;

import java.util.Map;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.net.HttpHeaders;

public class EIHeaders {

  private static final Map<String, String> GLOBAL_HEADERS =
      ImmutableSortedMap.<String, String>orderedBy(String.CASE_INSENSITIVE_ORDER)
          .put(HttpHeaders.STRICT_TRANSPORT_SECURITY, "max-age=31536000")
          .put(HttpHeaders.X_FRAME_OPTIONS, "deny")
          .put(HttpHeaders.X_CONTENT_TYPE_OPTIONS, "nosniff")
          .build();

  public static Map<String, String> getGlobalHeaders() {
    return GLOBAL_HEADERS;
  }

}
