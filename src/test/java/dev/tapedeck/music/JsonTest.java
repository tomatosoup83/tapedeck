package dev.tapedeck.music;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser tests, using payload shapes captured from real {@code busctl} output. */
class JsonTest {

	@Test
	void parsesAPropertiesGetAllReply() {
		String json = """
				{"type":"a{sv}","data":[{"PlaybackStatus":{"type":"s","data":"Playing"},\
				"Rate":{"type":"d","data":1.000000000000000000000e+00},\
				"Position":{"type":"x","data":30000000}}]}""";

		Map<String, Object> root = Json.asMap(Json.parse(json));
		assertEquals("a{sv}", root.get("type"));

		List<Object> data = Json.list(root.get("data"));
		assertEquals(1, data.size());

		Map<String, Object> properties = Json.asMap(data.get(0));
		assertEquals("Playing", Json.path(properties, "PlaybackStatus", "data"));
		assertEquals(30_000_000L, Json.asLong(Json.path(properties, "Position", "data"), 0L));
		assertEquals(1.0, Json.asDouble(Json.path(properties, "Rate", "data"), 0.0));
	}

	/**
	 * busctl's `call` wraps return values in a tuple, unlike `get-property` which returns
	 * the bare value. Getting this wrong silently yields "no players".
	 */
	@Test
	void callRepliesWrapValuesInATuple() {
		Map<String, Object> root = Json.asMap(Json.parse("{\"type\":\"as\",\"data\":[[\"a\",\"b\"]]}"));
		List<Object> tuple = Json.list(root.get("data"));

		assertEquals(1, tuple.size());
		assertEquals(List.of("a", "b"), Json.asList(tuple.get(0)));
	}

	@Test
	void keepsLargeIntegersExact() {
		Object value = Json.parse("{\"n\":198000000}");
		assertEquals(198_000_000L, Json.asLong(Json.path(value, "n"), 0L));
	}

	@Test
	void handlesEscapesAndUnicode() {
		Map<String, Object> root = Json.asMap(Json.parse("{\"s\":\"a\\\"b\\nc\\u00e9\"}"));
		assertEquals("a\"b\nc\u00e9", root.get("s"));
	}

	@Test
	void toleratesMissingKeys() {
		Object value = Json.parse("{\"a\":1}");
		assertNull(Json.path(value, "a", "b", "c"));
		assertEquals("", Json.asString(null, ""));
		assertNull(Json.asMap(Json.parse("[]")));
	}

	@Test
	void rejectsMalformedInput() {
		assertThrows(Json.JsonException.class, () -> Json.parse("{"));
		assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":}"));
		assertThrows(Json.JsonException.class, () -> Json.parse("null trailing"));
		assertThrows(Json.JsonException.class, () -> Json.parse(null));
		assertTrue(Json.list(null).isEmpty());
	}
}
