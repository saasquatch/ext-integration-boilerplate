package saasquatch.extintegration;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public class EIUtil {

	public static String urlEnc(String s) {
		try {
			return URLEncoder.encode(s, UTF_8.name());
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

}
