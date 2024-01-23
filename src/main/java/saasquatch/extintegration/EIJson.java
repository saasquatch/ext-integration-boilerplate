package saasquatch.extintegration;

import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collector;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ValueNode;

public final class EIJson {

  private EIJson() {}

  public static final ObjectMapper mapper() {
    return MapperHolder.MAPPER;
  }

  public static ObjectMapper newDefaultMapper() {
    final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
        .disable(JsonFactory.Feature.USE_THREAD_LOCAL_FOR_BUFFER_RECYCLING).build());
    return mapper;
  }

  public static <T extends JsonNode> Collector<T, ?, ArrayNode> toArrayNode() {
    return Collector.of(JsonNodeFactory.instance::arrayNode,
        ArrayNode::add,
        (a1, a2) -> {
          a1.addAll(a2);
          return a1;
        });
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

  public static ArrayNode arrayNodeOf(JsonNode... nodes) {
    final ArrayNode result = JsonNodeFactory.instance.arrayNode();
    for (JsonNode node : nodes) {
      result.add(node);
    }
    return result;
  }

  public static JsonNode mutateValueNodes(@Nonnull final JsonNode json,
      @Nonnull final Function<ValueNode, JsonNode> mutation) {
    if (json.isMissingNode()) {
      return json;
    } else if (json.isArray()) {
      final ArrayNode newNode = JsonNodeFactory.instance.arrayNode();
      for (JsonNode v : json) {
        newNode.add(mutateValueNodes(v, mutation));
      }
      return newNode;
    } else if (json.isObject()) {
      final ObjectNode newNode = JsonNodeFactory.instance.objectNode();
      json.fields().forEachRemaining(e -> {
        final String k = e.getKey();
        final JsonNode v = e.getValue();
        newNode.set(k, mutateValueNodes(v, mutation));
      });
      return newNode;
    } else {
      return mutation.apply((ValueNode) json);
    }
  }

  public static Map<String, Object> toMap(@Nullable JsonNode json) {
    return mapper().convertValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
  }

  public static Map<String, Object> toMap(@Nullable Object obj) {
    return mapper().convertValue(obj, new TypeReference<LinkedHashMap<String, Object>>() {});
  }

  public static String stringify(JsonNode json) {
    try {
      return mapper().writeValueAsString(json);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static byte[] toBytes(@Nonnull final JsonNode json) {
    try {
      return mapper().writeValueAsBytes(json);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static boolean hasArraysOfArrays(JsonNode j) {
    return hasArraysOfArrays(j, false);
  }

  private static boolean hasArraysOfArrays(JsonNode j, boolean parentIsArray) {
    if (j == null) {
      return false;
    } else if (j.isObject()) {
      final Iterator<Map.Entry<String, JsonNode>> fieldsIter = j.fields();
      while (fieldsIter.hasNext()) {
        final Map.Entry<String, JsonNode> entry = fieldsIter.next();
        if (hasArraysOfArrays(entry.getValue(), false)) {
          return true;
        }
      }
      return false;
    } else if (j.isArray()) {
      if (parentIsArray)
        return true;
      for (JsonNode arrayElem : j) {
        if (hasArraysOfArrays(arrayElem, true)) {
          return true;
        }
      }
      return false;
    } else {
      return false;
    }
  }

  private static final class MapperHolder {
    private static final ObjectMapper MAPPER = newDefaultMapper();
  }

}
