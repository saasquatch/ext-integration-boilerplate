package saasquatch.extintegration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nonnull;
import com.google.common.net.HttpHeaders;

public class EIHeaders {

  public static Map<String, String> getGlobalHeaders(@Nonnull Collection<String> frameSrc) {
    final List<String> csp = new ArrayList<>();
    csp.add("default-src https:");
    if (!frameSrc.isEmpty()) {
      csp.add("frame-src " + String.join(" ", frameSrc));
    }
    final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.put(HttpHeaders.CONTENT_SECURITY_POLICY, String.join("; ", csp));
    headers.put(HttpHeaders.X_FRAME_OPTIONS, "SAMEORIGIN");
    headers.put(HttpHeaders.X_CONTENT_TYPE_OPTIONS, "nosniff");
    headers.put(HttpHeaders.REFERRER_POLICY, "no-referrer-when-downgrade");
    headers.put("Feature-Policy", "none");
    return Collections.unmodifiableMap(headers);
  }

}
