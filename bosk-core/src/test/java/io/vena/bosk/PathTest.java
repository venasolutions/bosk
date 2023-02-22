package io.vena.bosk;

import io.vena.bosk.exceptions.MalformedPathException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static io.vena.bosk.BindingEnvironment.empty;
import static io.vena.bosk.PathTest.ValidityKind.ALWAYS;
import static io.vena.bosk.PathTest.ValidityKind.NEVER;
import static io.vena.bosk.PathTest.ValidityKind.NOT_IF_PARSED;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("Convert2MethodRef") // We use lambda syntax for consistency in here
class PathTest {

	static Stream<Arguments> validSegments() {
		return Stream.of(
			segments(), // empty
			segments("a"),
			segments("a","b"),
			segments("a/b"),
			segments("a%2Fb"), // Not a slash! Literally percent-2-F
			segments("-parameter-"),
			segments("-p1-"),
			segments("-p1-", "-p2-"),
			segments("-parameter_with_underscores-"),
			segments("-abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"),
			segments("tables", "-table-"),
			segments("tables", "-table-", "columns", "-column-")
		);
	}

	private static Arguments segments(String... segments) {
		return Arguments.of((Object) segments);
	}

	@ParameterizedTest
	@MethodSource("validSegments")
	void validSegments_stream_matches(String... segments) {
		assertEquals(asList(segments),
			Path.of(segments).segmentStream().collect(toList()));
	}

	@ParameterizedTest
	@MethodSource("validSegments")
	void validSegments_length_matches(String... segments) {
		assertEquals(segments.length,
			Path.of(segments).length());
	}

	@ParameterizedTest
	@MethodSource("validSegments")
	void validSegments_isEmpty_correct(String... segments) {
		assertEquals(segments.length == 0,
			Path.of(segments).isEmpty());
	}

	@ParameterizedTest
	@MethodSource("validSegments")
	void validSegments_parseRoundTrip_matches(String... segments) {
		Path path = Path.of(segments);
		assertEquals(path,
			Path.parseParameterized(path.urlEncoded()));
	}

	@ParameterizedTest
	@MethodSource("validSegments")
	void validSegments_toString_equalsUrlEncoded(String... segments) {
		Path path = Path.of(segments);
		String expected = path.urlEncoded();
		assertEquals(expected,
			path.toString());
	}

	@ParameterizedTest
	@MethodSource("validSegments")
	void validSegments_iterator_equalsStream(String... segments) {
		Path path = Path.of(segments);
		Iterator<String> expected = path.segmentStream().iterator();
		assertIteratorEquals(expected,
			path.iterator());
	}

	@Test
	void miscellaneous_length_matches() {
		assertEquals(0, Path.empty().length());
		assertEquals(0, Path.of(emptyList()).length());
		assertEquals(1, Path.just("a").length());
		assertEquals(2, Path.of("a","b").length());
		assertEquals(3, Path.of("a","-p1-","b").length());
	}

	@ParameterizedTest
	@MethodSource("malformedCases")
	void malformedPath_throws(String... segments) {
		assertThrows(MalformedPathException.class, () ->
			Path.of(segments));
	}

	static Stream<Arguments> malformedCases() {
		// "-" used to be a wildcard, but we no longer accept it
		return Stream.of(
			segments("-"),
			segments("a", "-"),
			segments("-", "a"),
			segments("a", "-", "b"),

			// Various invalid parameter names
			segments("noName", "--"),
			segments("missingDelimiter", "-bad"),
			segments("extraCharacters", "-bad-x"),
			segments("poop", "-💩-"),
			segments("number", "-5-"),
			segments("leadingUnderscore", "-_bad-"),
			segments("dollarSign", "-not$allowed-"),
			segments("nonLatin", "-bäd-"),
			segments("duplicateName", "-p1-", "-p1-")
		);
	}

	@Test
	void parse_slash() {
		assertEquals(Path.empty(), Path.parse("/"));
	}

	@Test
	void noLeadingSlash_throws() {
		assertThrows(MalformedPathException.class, () ->
			Path.parse("x"));
		assertThrows(MalformedPathException.class, () ->
			Path.parse("%2F")); // Not a slash! Literally percent-2-F
	}

	@Test
	void parse_A() {
		assertEquals(Path.just("a"), Path.parse("/a"));
	}

	@Test
	void parse_AB() {
		assertEquals(Path.of("a","b"), Path.parse("/a/b"));
	}

	@Test
	void parse_AslashB() {
		assertEquals(Path.just("a/b"), Path.parse("/a%2Fb"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"", "//", "a//", "/a//", "/a/", "//a",

		// For safety, parsed paths can't have parameters; they're only to be used programmatically
		"a/-/b", "a/-p1-/b"
	})
	void parse_invalid_throws(String urlEncoded) {
		assertThrows(MalformedPathException.class, ()->
			Path.parse(urlEncoded));
	}

	@Test
	void twoSegments_urlEncoded() {
		assertEquals("/a/b", Path.of("a","b").urlEncoded());
		assertEquals("/a/b", Path.of("a","b").toString());
	}

	@Test
	void segmentWithSlash_urlEncoded() {
		assertEquals("/a%2Fb", Path.just("a/b").urlEncoded());
		assertEquals("/a%2Fb", Path.just("a/b").toString());
	}

	@Test
	void segmentWithSpace_urlEncoded() {
		// We follow RFC 3986, which is not what Java's URLEncoder implements!
		assertEquals("/a%20b", Path.just("a b").urlEncoded());
		assertEquals("/a%20b", Path.just("a b").toString());
	}

	@ParameterizedTest
	@MethodSource("validSegments")
	void validSegments_ofList_correctSegments(String... segments) {
		List<String> expected = asList(segments);
		assertEquals(expected,
			Path.of(segments).segmentStream().collect(toList()));
	}

	@ParameterizedTest
	@MethodSource("validSegments")
	void validSegments_thenArray_correctSegments(String... segments) {
		List<String> expected = asList(segments);
		assertEquals(expected,
			Path.empty().then(segments).segmentStream().collect(toList()));
	}

	@ParameterizedTest
	@MethodSource("validSegments")
	void validSegments_thenList_correctSegments(String... segments) {
		List<String> expected = asList(segments);
		assertEquals(expected,
			Path.empty().then(expected).segmentStream().collect(toList()));
	}

	@ParameterizedTest
	@MethodSource("validSegments")
	void validSegments_ofList_equalsThenArray(String... segments) {
		Path expected = Path.empty().then(segments);
		assertEquals(expected,
			Path.of(segments));
	}

	private static <T> void assertIteratorEquals(Iterator<? super T> expected, Iterator<? super T> actual) {
		while (expected.hasNext()) {
			assertTrue(actual.hasNext());
			assertEquals(expected.next(), actual.next());
		}
		assertFalse(actual.hasNext());
	}

	@ParameterizedTest
	@MethodSource("validSegments")
	void validPath_isPrefixOfItself(String... segments) {
		Path path = Path.of(segments);
		assertTrue(path.isPrefixOf(path));
	}

	@ParameterizedTest
	@MethodSource("prefixPairs")
	void isPrefix(Path left, Path right) {
		assertTrue(left.isPrefixOf(right));
	}

	static Stream<Arguments> prefixPairs() {
		return Stream.of(
			Arguments.of(Path.empty(), Path.just("a")),
			Arguments.of(Path.empty(), Path.of("a","b")),
			Arguments.of(Path.empty(), Path.just("a/b")),
			Arguments.of(Path.just("a"), Path.of("a","b")),
			Arguments.of(Path.of("a","b"), Path.of("a","b","c")),
			Arguments.of(Path.of("a","b","c"), Path.of("a","b","c","d")),
			Arguments.of(Path.of("a","-p1-","c"), Path.of("a","-p1-","c","d"))
		);
	}

	@ParameterizedTest
	@MethodSource("notPrefixPairs")
	void isNotPrefix(Path left, Path right) {
		assertFalse(left.isPrefixOf(right));
	}

	static Stream<Arguments> notPrefixPairs() {
		return Stream.of(
			Arguments.of(Path.just("a"), Path.empty()),

			Arguments.of(Path.of("a","b"), Path.empty()),
			Arguments.of(Path.of("a","b"), Path.just("a")),
			Arguments.of(Path.of("a","b"), Path.of("a","c")),

			Arguments.of(Path.of("a","b","c"), Path.empty()),
			Arguments.of(Path.of("a","b","c"), Path.just("a")),
			Arguments.of(Path.of("a","b","c"), Path.of("a","b")),
			Arguments.of(Path.of("a","b","c"), Path.of("a","b","d")),

			Arguments.of(Path.of("a","-p1-","c"), Path.empty()),
			Arguments.of(Path.of("a","-p1-","c"), Path.just("a")),
			Arguments.of(Path.of("a","-p1-","c"), Path.of("a","-p1-")),
			Arguments.of(Path.of("a","-p1-","c"), Path.of("a","-p1-","d")),
			Arguments.of(Path.of("a","-p1-","c"), Path.of("a","b","c")),
			Arguments.of(Path.of("a","-p1-","c"), Path.of("a","b","c","d")),

			// Some other oddball cases
			Arguments.of(Path.of("a","b"), Path.just("b")),
			Arguments.of(Path.just("a"),   Path.just("a/b")),
			Arguments.of(Path.just("a/b"), Path.just("a")),
			Arguments.of(Path.of("a","b"), Path.just("a/b")),
			Arguments.of(Path.just("a/b"), Path.of("a","b"))
		);
	}

	@ParameterizedTest
	@MethodSource("validSegments")
	void validSegments_truncationWorks(String... segments) {
		Path path = Path.of(segments);
		assertEquals(path, path.truncatedBy(0));
		assertEquals(path, path.truncatedTo(path.length()));
		for (int numTruncatedBy = 0; numTruncatedBy < segments.length; numTruncatedBy++) {
			int numTruncatedTo = segments.length - numTruncatedBy;
			String[] truncated = Arrays.copyOf(segments, numTruncatedTo);
			Path expected = Path.of(truncated);
			assertEquals(expected,
				path.truncatedBy(numTruncatedBy));
			assertEquals(expected,
				path.truncatedTo(numTruncatedTo));
		}
		assertThrows(IllegalArgumentException.class, () ->
			path.truncatedBy(-1));
		assertThrows(IllegalArgumentException.class, () ->
			path.truncatedTo(-1));
		assertThrows(IllegalArgumentException.class, () ->
			path.truncatedBy(path.length()+1));
		assertThrows(IllegalArgumentException.class, () ->
			path.truncatedTo(path.length()+1));
	}

	@ParameterizedTest
	@MethodSource("validSegments")
	void validSegments_segmentWorks(String... segments) {
		Path path = Path.of(segments);
		for (int i = 0; i < segments.length; i++) {
			assertEquals(segments[i], path.segment(i));
		}
		assertThrows(IllegalArgumentException.class, () ->
			path.segment(-1));
		assertThrows(IllegalArgumentException.class, () ->
			path.segment(path.length()));
	}

	@ParameterizedTest
	@MethodSource("validSegments")
	void validSegments_lastSegmentWorks(String... segments) {
		Path path = Path.of(segments);
		if (segments.length == 0) {
			assertThrows(IllegalArgumentException.class, () ->
				path.lastSegment());
		} else {
			String expected = segments[segments.length - 1];
			assertEquals(expected,
				path.lastSegment());
		}
	}

	@ParameterizedTest
	@MethodSource("validSegments")
	@SuppressWarnings({"SimplifiableAssertion", "EqualsWithItself"}) // We're explicitly calling "equals" here, and that's ok
	void validSegments_equalsItself(String... segments) {
		Path path = Path.of(segments);
		assertTrue(path.equals(path));
	}

	@ParameterizedTest
	@MethodSource("unequalPaths")
	@SuppressWarnings({"SimplifiableAssertion"}) // We're explicitly calling "equals" here, and that's ok
	void unequalPaths_notEqual(Path left, Path right) {
		assertFalse(left.equals(right));
	}

	static Stream<Arguments> unequalPaths() {
		return Stream.of(
			Arguments.of(Path.of("a","b"), Path.just("a")),
			Arguments.of(Path.just("a"), Path.of("a","b")),
			Arguments.of(Path.of("a","b"), Path.just("a/b")),
			Arguments.of(Path.of("a","-p1-"), Path.just("a/b")),
			Arguments.of(Path.of("a","-p1-"), Path.of("a","-p2-"))
		);
	}

	@Test
	@SuppressWarnings("deprecation") // We're intentionally testing methods that are never meant to be called
	void invalidOf_throws() {
		assertThrows(IllegalArgumentException.class, () -> Path.of());
		assertThrows(IllegalArgumentException.class, () -> Path.of("a"));
	}

	@ParameterizedTest
	@MethodSource("parameterCases")
	void testParameters(int numParameters, Integer firstParameterIndex, Path path) {
		assertEquals(numParameters, path.numParameters());
		if (firstParameterIndex == null) {
			assertThrows(IllegalArgumentException.class, () ->
				path.firstParameterIndex());
		} else {
			assertEquals(firstParameterIndex,
				path.firstParameterIndex());
		}
	}

	static Stream<Arguments> parameterCases() {
		return Stream.of(
			Arguments.of(0, null, Path.parse("/a/b")),
			Arguments.of(1, 1, Path.of("a", "-p-")),
			Arguments.of(0, null, Path.of("a","-p-").boundBy(singletonBinding("p","b"))),
			Arguments.of(1, 0, Path.just("-p1-")),
			Arguments.of(2, 0, Path.of("-p1-", "-p2-")),
			Arguments.of(3, 0, Path.of("-p1-", "-p2-", "-p3-")),
			Arguments.of(1, 3, Path.of("a", "b", "c", "-p1-")),
			Arguments.of(2, 3, Path.of("a", "b", "c", "-p1-", "-p2-")),
			Arguments.of(2, 1, Path.of("a", "-p1-", "b", "c", "-p2-")),
			Arguments.of(0, null, Path.empty()),
			Arguments.of(0, null, Path.just("a")),
			Arguments.of(0, null, Path.of("a","b"))
		);
	}

	@ParameterizedTest
	@MethodSource("parametersFromPathCases")
	void testParametersFromPath(BindingEnvironment expected, String parameterized, String concrete) {
		Path concretePath = Path.parse(concrete);
		Path parameterizedPath = Path.parseParameterized(parameterized);
		if (expected == null) {
			assertThrows(AssertionError.class, () ->
				parameterizedPath.parametersFrom(concretePath));
		} else {
			assertEquals(expected,
				parameterizedPath.parametersFrom(concretePath));
		}
	}

	static Stream<Arguments> parametersFromPathCases() {
		return Stream.of(
			Arguments.of(singletonBinding("p", "a"), "/-p-", "/a"),
			Arguments.of(singletonBinding("p", "a"), "/-p-", "/a/b"),
			Arguments.of(singletonBinding("p", "b"), "/a/-p-", "/a/b"),
			Arguments.of(singletonBinding("p", "a"), "/-p-/b", "/a/b"),
			Arguments.of(singletonBinding("p", "a"), "/-p-/b", "/a"),
			Arguments.of(empty(), "/-p1-/-p2-", "/"),
			Arguments.of(singletonBinding("p1", "a"), "/-p1-/-p2-", "/a"),
			Arguments.of(doubletonBinding("p1", "a", "p2", "b"), "/-p1-/-p2-", "/a/b"),
			Arguments.of(doubletonBinding("p1", "a", "p2", "b"), "/-p1-/-p2-", "/a/b/c/d"),
			Arguments.of(doubletonBinding("p1", "b", "p2", "d"), "/a/-p1-/c/-p2-/e", "/a/b/c/d/e"),
			Arguments.of(empty(), "/", "/"),
			Arguments.of(empty(), "/a/b", "/a/b"),
			Arguments.of(null, "/b", "/a"),
			Arguments.of(null, "/b/-p-", "/a/b")
		);
	}

	@ParameterizedTest
	@MethodSource("parametersFromListCases")
	void testParametersFromPath(BindingEnvironment expected, String parameterized, Collection<String> idStrings) {
		Collection<Identifier> ids = idStrings.stream().map(Identifier::from).collect(toList());
		Path parameterizedPath = Path.parseParameterized(parameterized);
		if (expected == null) {
			assertThrows(IllegalArgumentException.class, () ->
				parameterizedPath.parametersFrom(ids));
		} else {
			assertEquals(expected,
				parameterizedPath.parametersFrom(ids));
		}
	}

	static Stream<Arguments> parametersFromListCases() {
		return Stream.of(
			Arguments.of(empty(), "/", emptyList()),
			Arguments.of(empty(), "/-p-", emptyList()),
			Arguments.of(empty(), "/-p1-/-p2-", emptyList()),
			Arguments.of(singletonBinding("p", "x"), "/a/-p-", singletonList("x")),
			Arguments.of(singletonBinding("p", "x"), "/-p-/b", singletonList("x")),
			Arguments.of(singletonBinding("p", "x"), "/a/-p-/b", singletonList("x")),
			Arguments.of(singletonBinding("p1", "x"), "/-p1-/-p2-", singletonList("x")),
			Arguments.of(singletonBinding("p1", "x"), "/a/-p1-/-p2-", singletonList("x")),
			Arguments.of(singletonBinding("p1", "x"), "/-p1-/b/-p2-", singletonList("x")),
			Arguments.of(singletonBinding("p1", "x"), "/-p1-/-p2-/c", singletonList("x")),
			Arguments.of(singletonBinding("p1", "x"), "/a/-p1-/b/-p2-/c", singletonList("x")),
			Arguments.of(doubletonBinding("p1", "x", "p2", "y"), "/-p1-/-p2-", asList("x","y")),
			Arguments.of(doubletonBinding("p1", "x", "p2", "y"), "/a/-p1-/-p2-", asList("x","y")),
			Arguments.of(doubletonBinding("p1", "x", "p2", "y"), "/-p1-/b/-p2-", asList("x","y")),
			Arguments.of(doubletonBinding("p1", "x", "p2", "y"), "/-p1-/-p2-/c", asList("x","y")),
			Arguments.of(doubletonBinding("p1", "x", "p2", "y"), "/a/-p1-/b/-p2-/c", asList("x","y")),

			// Too many parameters
			Arguments.of(null, "/", asList("x", "y")),
			Arguments.of(null, "/-p-", asList("x", "y")),
			Arguments.of(null, "/a/-p-", asList("x", "y")),
			Arguments.of(null, "/-p-/b", asList("x", "y")),
			Arguments.of(null, "/a/-p-/b", asList("x", "y")),
			Arguments.of(null, "/-p1-/-p2-", asList("x", "y", "z")),
			Arguments.of(null, "/a/-p1-/-p2-", asList("x", "y", "z")),
			Arguments.of(null, "/-p1-/b/-p2-", asList("x", "y", "z")),
			Arguments.of(null, "/-p1-/-p2-/c", asList("x", "y", "z")),
			Arguments.of(null, "/a/-p1-/b/-p2-/c", asList("x", "y", "z"))
		);
	}

	@ParameterizedTest
	@MethodSource("bindingCases")
	void testBoundBy(Path expected, Path path, BindingEnvironment env) {
		assertEquals(expected,
			path.boundBy(env));
	}

	static Stream<Arguments> bindingCases() {
		Path def = Path.of("a", "b");
		Path indef = Path.of("a", "-p1-", "b", "-p2-");
		return Stream.of(
			Arguments.of(indef, indef, empty()),
			Arguments.of(Path.of("a","x","b","-p2-"), indef, singletonBinding("p1", "x")),
			Arguments.of(Path.of("a","-p1-","b","y"), indef, singletonBinding("p2", "y")),
			Arguments.of(Path.of("a","x","b","y"), indef, doubletonBinding("p1", "x", "p2", "y")),
			// Binding has no effect on a definite reference
			Arguments.of(def, def, empty()),
			Arguments.of(def, def, singletonBinding("p1", "x")),
			Arguments.of(def, def, singletonBinding("p2", "y")),
			Arguments.of(def, def, doubletonBinding("p1", "x", "p2", "y"))
		);
	}

	@Test
	void emptyParameterName_throws() {
		assertThrows(IllegalArgumentException.class, () ->
			singletonBinding("", "x"));
	}

	@Test
	void emptyParameterValue_throws() {
		assertThrows(IllegalArgumentException.class, () ->
			singletonBinding("p", ""));
	}

	@ParameterizedTest
	@MethodSource("validSegmentCases")
	void testValidSegment(ValidityKind kind, String segment) {
		switch (kind) {
			case NEVER:
				assertThrows(IllegalArgumentException.class, () ->
					Path.validSegment(segment));
				assertThrows(IllegalArgumentException.class, () ->
					Path.validParsedSegment(segment));
				break;
			case ALWAYS:
				assertEquals(segment,
					Path.validSegment(segment));
				assertEquals(segment,
					Path.validParsedSegment(segment));
				break;
			case NOT_IF_PARSED:
				assertEquals(segment,
					Path.validSegment(segment));
				assertThrows(IllegalArgumentException.class, () ->
					Path.validParsedSegment(segment));
				break;
		}
	}

	static Stream<Arguments> validSegmentCases() {
		return Stream.of(
			Arguments.of(ALWAYS, "a"),
			Arguments.of(ALWAYS, "a/b"),
			Arguments.of(NEVER, null),
			Arguments.of(NEVER, ""),
			Arguments.of(NEVER, "-"),
			Arguments.of(NEVER, "-a"),
			Arguments.of(NEVER, "-123"),
			Arguments.of(NEVER, "-123-"),
			Arguments.of(NEVER, "-💩-"),
			Arguments.of(NOT_IF_PARSED, "-p1-")
		);
	}

	enum ValidityKind {
		NEVER,
		ALWAYS,
		NOT_IF_PARSED
	}

	@ParameterizedTest
	@MethodSource("matchesCases")
	void testMatches(boolean expected, Path pattern, Path path) {
		assertEquals(expected,
			pattern.matches(path));
		assertEquals(expected,
			path.matches(pattern));
	}

	static Stream<Arguments> matchesCases() {
		Path empty = Path.empty();
		Path a_b = Path.of("a", "b");
		Path a_p1_b = Path.of("a", "-p1-", "b");
		Path a_1_b = Path.of("a", "1", "b");
		Path a_p1_b_p2 = Path.of("a", "-p1-", "b", "-p2-");
		Path a_1_b_p1 = Path.of("a", "1", "b", "-p1-");
		Path a_1_b_p2 = Path.of("a", "1", "b", "-p2-");
		Path a_p1_b_2 = Path.of("a", "-p1-", "b", "2");
		Path a_1_b_2 = Path.of("a", "1", "b", "2");
		List<Path> definite = asList(empty, a_b, a_1_b, a_1_b_2);
		List<Path> all = asList(empty, a_b, a_p1_b, a_1_b, a_p1_b_p2, a_1_b_p1, a_1_b_p2, a_p1_b_2, a_1_b_2);

		// Set up a structure describing the expected results
		Map<Path, List<Path>> matches = new HashMap<>();
		definite.forEach(p -> matches.put(p, singletonList(p))); // Every definite path matches itself
		putEquivalenceClass(matches, a_1_b, a_p1_b);
		putEquivalenceClass(matches, a_p1_b_2, a_p1_b_p2, a_1_b_p1, a_1_b_p2, a_1_b_2);

		// Exhaustively test all pairs against the expected results
		return all.stream().flatMap(pattern -> all.stream().map(path ->
				Arguments.of(matches.get(pattern).contains(path), pattern, path)));
	}

	/**
	 * Establishes that all the given paths mutually match each other
	 */
	private static void putEquivalenceClass(Map<Path, List<Path>> matches, Path... paths) {
		for (Path key: paths) {
			matches.put(key, asList(paths));
		}
	}

	@Test
	void testInterning() {
		Path outerPath = Path.of("outer", "-outer-");
		Path innerPath = outerPath.then("inner", "-inner-");
		List<Path> paths = new ArrayList<>();
		Random random = new Random(123);
		for (int i = 0; i < 20; i++) {
			BindingEnvironment env = BindingEnvironment.singleton("outer", Identifier.from("outer_" + random.nextInt(10)));
			paths.add(outerPath.boundBy(env));
			for (int j = 0; j < 10; j++) {
				paths.add(innerPath.boundBy(
					env.builder()
						.bind("inner", Identifier.from("inner_" + random.nextInt(10)))
						.build()));
			}

			// Try to fool the Path interning logic into messing up its internal WeakHashMaps
			System.gc();
		}

		for (Path p: paths) {
			assertSame(p, Path.parse(p.urlEncoded()));
		}
	}

	private static BindingEnvironment singletonBinding(String name, String value) {
		return BindingEnvironment.singleton(name, Identifier.from(value));
	}

	private static BindingEnvironment doubletonBinding(String name1, String value1, String name2, String value2) {
		return empty().builder()
			.bind(name1, Identifier.from(value1))
			.bind(name2, Identifier.from(value2))
			.build();
	}

}
