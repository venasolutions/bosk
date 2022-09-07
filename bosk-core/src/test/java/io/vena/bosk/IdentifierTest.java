package io.vena.bosk;

import io.vena.bosk.junit.ParametersByName;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class IdentifierTest {

	@ParametersByName
	void validString_survivesRoundTrip(String validString) {
		assertEquals(validString, Identifier.from(validString).toString());
	}

	@ParametersByName
	void invalidString_throws(String invalidString) {
		assertThrows(IllegalArgumentException.class, () -> Identifier.from(invalidString));
	}

	@SuppressWarnings("unused")
	static Stream<String> validString() {
		return Stream.of(
			"test",
			"unicode\uD83C\uDF33",
			"name with spaces",
			"name/with/slashes",
			"name.with.dots",
			"name\nwith\nnewlines",
			"name\twith\ttabs"
		);
	}

	@SuppressWarnings("unused")
	static Stream<String> invalidString() {
		return Stream.of(
			"",
			"-startsWithDash",
			"endsWithDash-",
			"-startsAndEndsWithDash-"
		);
	}
}
