package saasquatch.extintegration;

import java.util.function.Supplier;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Suppliers;

public class EIJson {

  private static final Supplier<ObjectMapper> MAPPER = Suppliers.memoize(EIJson::newDefaultMapper);

  public static final ObjectMapper mapper() {
    return MAPPER.get();
  }

  public static ObjectMapper newDefaultMapper() {
    final ObjectMapper mapper = new ObjectMapper();
    mapper.getFactory().disable(JsonFactory.Feature.USE_THREAD_LOCAL_FOR_BUFFER_RECYCLING);
    return mapper;
  }

}
