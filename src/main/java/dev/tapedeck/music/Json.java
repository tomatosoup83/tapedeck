package dev.tapedeck.music;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberately small JSON reader for {@code busctl --json=short} output.
 *
 * <p>MPRIS data arrives through {@code busctl}, whose JSON dialect is ordinary JSON:
 * objects, arrays, strings, numbers, booleans and null. The shapes we consume are tiny
 * (a variant tree a few levels deep), so a 200-line recursive-descent reader keeps the
 * mod free of extra dependencies — Minecraft bundles Gson, but nothing here justifies
 * pulling it into the media path, and this class is trivially unit-testable on its own.
 *
 * <p>Values are returned as {@link Map}, {@link List}, {@link String}, {@link Long},
 * {@link Double} or {@link Boolean}. Integral numbers stay {@code Long} so microsecond
 * timestamps survive without going through a double.
 */
public final class Json {

	/** Guards against pathological input nesting; D-Bus payloads are nowhere near this. */
	private static final int MAX_DEPTH = 64;

	private final String text;
	private int pos;

	private Json(String text) {
		this.text = text;
	}

	public static final class JsonException extends RuntimeException {
		public JsonException(String message) {
			super(message);
		}
	}

	/** Parses one complete JSON value; throws {@link JsonException} on anything malformed. */
	public static Object parse(String text) {
		if (text == null) {
			throw new JsonException("null input");
		}
		Json reader = new Json(text);
		reader.skipWhitespace();
		Object value = reader.readValue(0);
		reader.skipWhitespace();
		if (reader.pos != text.length()) {
			throw new JsonException("trailing data at offset " + reader.pos);
		}
		return value;
	}

	// ------------------------------------------------------------------------ reading

	private Object readValue(int depth) {
		if (depth > MAX_DEPTH) {
			throw new JsonException("nesting too deep at offset " + pos);
		}
		if (pos >= text.length()) {
			throw new JsonException("unexpected end of input");
		}
		char c = text.charAt(pos);
		return switch (c) {
			case '{' -> readObject(depth);
			case '[' -> readArray(depth);
			case '"' -> readString();
			case 't' -> readLiteral("true", Boolean.TRUE);
			case 'f' -> readLiteral("false", Boolean.FALSE);
			case 'n' -> readLiteral("null", null);
			default -> readNumber();
		};
	}

	private Object readLiteral(String literal, Object value) {
		if (!text.startsWith(literal, pos)) {
			throw new JsonException("bad literal at offset " + pos);
		}
		pos += literal.length();
		return value;
	}

	private Map<String, Object> readObject(int depth) {
		Map<String, Object> map = new LinkedHashMap<>();
		pos++; // '{'
		skipWhitespace();
		if (peek() == '}') {
			pos++;
			return map;
		}
		while (true) {
			skipWhitespace();
			if (peek() != '"') {
				throw new JsonException("expected key at offset " + pos);
			}
			String key = readString();
			skipWhitespace();
			expect(':');
			skipWhitespace();
			map.put(key, readValue(depth + 1));
			skipWhitespace();
			char c = peek();
			if (c == ',') {
				pos++;
			} else if (c == '}') {
				pos++;
				return map;
			} else {
				throw new JsonException("expected ',' or '}' at offset " + pos);
			}
		}
	}

	private List<Object> readArray(int depth) {
		List<Object> list = new ArrayList<>();
		pos++; // '['
		skipWhitespace();
		if (peek() == ']') {
			pos++;
			return list;
		}
		while (true) {
			skipWhitespace();
			list.add(readValue(depth + 1));
			skipWhitespace();
			char c = peek();
			if (c == ',') {
				pos++;
			} else if (c == ']') {
				pos++;
				return list;
			} else {
				throw new JsonException("expected ',' or ']' at offset " + pos);
			}
		}
	}

	private String readString() {
		pos++; // opening quote
		StringBuilder sb = new StringBuilder();
		while (true) {
			if (pos >= text.length()) {
				throw new JsonException("unterminated string");
			}
			char c = text.charAt(pos++);
			if (c == '"') {
				return sb.toString();
			}
			if (c != '\\') {
				sb.append(c);
				continue;
			}
			if (pos >= text.length()) {
				throw new JsonException("unterminated escape");
			}
			char esc = text.charAt(pos++);
			switch (esc) {
				case '"' -> sb.append('"');
				case '\\' -> sb.append('\\');
				case '/' -> sb.append('/');
				case 'b' -> sb.append('\b');
				case 'f' -> sb.append('\f');
				case 'n' -> sb.append('\n');
				case 'r' -> sb.append('\r');
				case 't' -> sb.append('\t');
				case 'u' -> {
					if (pos + 4 > text.length()) {
						throw new JsonException("short \\u escape");
					}
					String hex = text.substring(pos, pos + 4);
					pos += 4;
					try {
						sb.append((char) Integer.parseInt(hex, 16));
					} catch (NumberFormatException e) {
						throw new JsonException("bad \\u escape: " + hex);
					}
				}
				default -> throw new JsonException("bad escape \\" + esc);
			}
		}
	}

	private Object readNumber() {
		int start = pos;
		if (peek() == '-' || peek() == '+') {
			pos++;
		}
		boolean floating = false;
		while (pos < text.length()) {
			char c = text.charAt(pos);
			if (c >= '0' && c <= '9') {
				pos++;
			} else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
				floating = true;
				pos++;
			} else {
				break;
			}
		}
		String token = text.substring(start, pos);
		if (token.isEmpty() || token.equals("-") || token.equals("+")) {
			throw new JsonException("bad number at offset " + start);
		}
		if (!floating) {
			try {
				return Long.parseLong(token);
			} catch (NumberFormatException ignored) {
				// Too large for a long: fall through to a double rather than failing.
			}
		}
		try {
			return Double.parseDouble(token);
		} catch (NumberFormatException e) {
			throw new JsonException("bad number: " + token);
		}
	}

	private void expect(char expected) {
		if (peek() != expected) {
			throw new JsonException("expected '" + expected + "' at offset " + pos);
		}
		pos++;
	}

	private char peek() {
		if (pos >= text.length()) {
			throw new JsonException("unexpected end of input");
		}
		return text.charAt(pos);
	}

	private void skipWhitespace() {
		while (pos < text.length()) {
			char c = text.charAt(pos);
			if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
				pos++;
			} else {
				return;
			}
		}
	}

	// ---------------------------------------------------------------------- accessors

	@SuppressWarnings("unchecked")
	public static Map<String, Object> asMap(Object value) {
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> asList(Object value) {
		return value instanceof List<?> list ? (List<Object>) list : null;
	}

	public static Map<String, Object> map(Object value) {
		Map<String, Object> map = asMap(value);
		return map == null ? Map.of() : map;
	}

	public static List<Object> list(Object value) {
		List<Object> list = asList(value);
		return list == null ? List.of() : list;
	}

	public static String asString(Object value, String fallback) {
		if (value instanceof String s) {
			return s;
		}
		if (value instanceof Number || value instanceof Boolean) {
			return String.valueOf(value);
		}
		return fallback;
	}

	public static long asLong(Object value, long fallback) {
		if (value instanceof Number n) {
			return n.longValue();
		}
		if (value instanceof String s) {
			try {
				return Long.parseLong(s.trim());
			} catch (NumberFormatException ignored) {
				return fallback;
			}
		}
		return fallback;
	}

	public static double asDouble(Object value, double fallback) {
		if (value instanceof Number n) {
			return n.doubleValue();
		}
		if (value instanceof String s) {
			try {
				return Double.parseDouble(s.trim());
			} catch (NumberFormatException ignored) {
				return fallback;
			}
		}
		return fallback;
	}

	public static boolean asBoolean(Object value, boolean fallback) {
		if (value instanceof Boolean b) {
			return b;
		}
		if (value instanceof String s) {
			return Boolean.parseBoolean(s.trim());
		}
		return fallback;
	}

	// ------------------------------------------------------------------- convenience

	/** Walks nested objects, returning {@code null} if any step is missing. */
	public static Object path(Object root, String... keys) {
		Object node = root;
		for (String key : keys) {
			Map<String, Object> map = asMap(node);
			if (map == null) {
				return null;
			}
			node = map.get(key);
			if (node == null) {
				return null;
			}
		}
		return node;
	}
}
