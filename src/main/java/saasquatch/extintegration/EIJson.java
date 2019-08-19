package saasquatch.extintegration;

import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ValueNode;
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
		} else if (json.isValueNode()) {
			return mutation.apply((ValueNode) json);
		} else {
			final ObjectNode newNode = JsonNodeFactory.instance.objectNode();
			json.fields().forEachRemaining(e -> {
				final String k = e.getKey();
				final JsonNode v = e.getValue();
				newNode.set(k, mutateValueNodes(v, mutation));
			});
			return newNode;
		}
	}

}
