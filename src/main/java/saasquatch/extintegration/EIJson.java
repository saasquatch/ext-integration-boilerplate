package saasquatch.extintegration;

import java.util.function.Supplier;
import javax.annotation.Nullable;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Suppliers;

public class EIJson {

  private static final Supplier<ObjectMapper> MAPPER = Suppliers.memoize(EIJson::newDefaultMapper);

  public static final ObjectMapper mapper() {
    return MAPPER.get();
  }

  public static ObjectMapper newDefaultMapper() {
    final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
        .disable(JsonFactory.Feature.USE_THREAD_LOCAL_FOR_BUFFER_RECYCLING)
        .build());
    return mapper;
  }

  public static boolean isNull(@Nullable JsonNode j) {
    return j == null || j.isMissingNode() || j.isNull();
  }

  public static boolean nonNull(@Nullable JsonNode j) {
    return !isNull(j);
  }

  public static boolean isEmpty(@Nullable JsonNode j) {
    if (isNull(j))
      return true;
    if (j.isValueNode())
      return false;
    return j.size() == 0;
  }

  public static boolean nonEmpty(@Nullable JsonNode j) {
    return !isEmpty(j);
  }

}
