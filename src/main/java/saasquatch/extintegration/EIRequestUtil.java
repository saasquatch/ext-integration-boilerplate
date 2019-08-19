package saasquatch.extintegration;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Base64;
import java.util.Optional;

import javax.annotation.Nullable;

import org.apache.commons.lang3.tuple.Pair;

public class EIRequestUtil {

	public static Optional<Pair<String, String>> getBasicAuth(@Nullable String authorizationHeader) {
		return Optional.ofNullable(authorizationHeader)
				.filter(s -> s.startsWith("Basic "))
				.map(s -> s.substring("Basic ".length()))
				.map(t -> {
					try {
						return Base64.getDecoder().decode(t);
					} catch (IllegalArgumentException e) {
						return null; // invalid base64
					}
				})
				.map(b -> new String(b, UTF_8))
				.filter(s -> s.contains(":"))
				.map(s -> s.split(":", 2))
				.filter(t -> t.length == 2)
				.map(t -> Pair.of(t[0], t[1]));
	}

}
