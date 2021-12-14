package org.vena.bosk;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.vena.bosk.exceptions.MalformedPathException;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

@SuppressWarnings("Convert2MethodRef") // We use lambda syntax for consistency in here
class PathTest {

	@TestFactory
	Stream<DynamicTest> testPaths() {
		return Stream.of(
			validPathTest(), // empty
			validPathTest("a"),
			validPathTest("a","b"),
			validPathTest("a/b"),
			validPathTest("-parameter-"),
			validPathTest("-p1-"),
			validPathTest("-p1-", "-p2-"),
			validPathTest("-parameter_with_underscores-"),
			validPathTest("-abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"),
			validPathTest("tables", "-table-"),
			validPathTest("tables", "-table-", "columns", "-column-"),

			// "-" used to be a wildcard, but we no longer accept it
			invalidPathTest("-"),
			invalidPathTest("a", "-"),
			invalidPathTest("-", "a"),
			invalidPathTest("a", "-", "b"),

			// Various invalid parameter names
			invalidPathTest("noName", "--"),
			invalidPathTest("missingDelimiter", "-bad"),
			invalidPathTest("extraCharacters", "-bad-x"),
			invalidPathTest("poop", "-💩-"),
			invalidPathTest("number", "-5-"),
			invalidPathTest("leadingUnderscore", "-_bad-"),
			invalidPathTest("dollarSign", "-not$allowed-"),
			invalidPathTest("nonLatin", "-bäd-"),
			invalidPathTest("duplicateName", "-p1-", "-p1-")
		);
	}

	private DynamicTest validPathTest(String... segments) {
		return dynamicTest("Path.of(" + Arrays.toString(segments) + ")", () ->
			assertEquals(asList(segments), Path.of(segments).segmentStream().collect(toList())));
	}

	private DynamicTest invalidPathTest(String... segments) {
		return dynamicTest("Path.of(" + Arrays.toString(segments) + ")", () ->
			assertThrows(MalformedPathException.class, ()->Path.of(segments)));
	}

	@TestFactory
	Stream<DynamicTest> testLength() {
		return Stream.of(
			lengthTests(0, Path.empty()),
			lengthTests(0, Path.of(emptyList())),
			lengthTests(1, Path.just("a")),
			lengthTests(2, Path.of("a","b")),
			lengthTests(3, Path.of("a","-p1-","b"))
		).flatMap(identity());
	}

	private Stream<DynamicTest> lengthTests(int expectedLength, Path path) {
		return Stream.of(
			dynamicTest("(" + path + ").length()", () -> assertEquals(expectedLength, path.length())),
			dynamicTest("(" + path + ").isEmpty()", () -> assertEquals((expectedLength == 0), path.isEmpty()))
		);
	}

	@TestFactory
	Iterable<DynamicTest> testParse() {
		return asList(
			parseTest(Path.empty(), "/"),
			parseTest(Path.just("a"), "/a"),
			parseTest(Path.of("a","b"), "/a/b"),
			parseTest(Path.just("a/b"), "/a%2Fb"),

			invalidParseTest(""),
			invalidParseTest("//"),
			invalidParseTest("a//"),
			invalidParseTest("/a//"),
			invalidParseTest("/a/"),
			invalidParseTest("//a"),

			// For safety, parsed paths can't have parameters; they're only to be used programmatically
			invalidParseTest("a/-/b"),
			invalidParseTest("a/-p1-/b")
		);
	}

	private DynamicTest parseTest(Path expected, String urlEncoded) {
		return dynamicTest("parse(" + urlEncoded + ")", () -> assertEquals(expected, Path.parse(urlEncoded)));
	}

	private DynamicTest invalidParseTest(String urlEncoded) {
		return dynamicTest("!parse(" + urlEncoded + ")", () -> assertThrows(MalformedPathException.class, ()-> Path.parse(urlEncoded)));
	}

	@TestFactory
	Stream<DynamicTest> testURLEncode() {
		return Stream.of(
			urlEncodeTest("/a/b", "a", "b"),
			urlEncodeTest("/a%2Fb", "a/b"),
			urlEncodeTest("/a%20b", "a b") // We follow RFC 3986, which is not what Java's URLEncoder implements!
		).flatMap(s->s);
	}

	private Stream<DynamicTest> urlEncodeTest(String expected, String... segments) {
		return Stream.of(
			dynamicTest("urlEncoded(" + Arrays.toString(segments) + ")", () ->
				assertEquals(expected, Path.of(segments).urlEncoded())),
			dynamicTest("toString(" + Arrays.toString(segments) + ")", () ->
				assertEquals(expected, Path.of(segments).toString()))

		);
	}

	@TestFactory
	Iterable<DynamicTest> testSundryProperties() {
		return Stream.of(Path.empty(),
			Path.just("a"),
			Path.of("a","b"),
			Path.just("a%2Fb"))
			.flatMap(p->Stream.of(
				dynamicTest(format("Round trip \"%s\"", p), ()->assertEquals(p, Path.parse(p.urlEncoded()))),
				dynamicTest(format("toString \"%s\"", p),   ()->assertEquals(p.urlEncoded(), p.toString())),
				dynamicTest(format("iterator \"%s\"", p),   ()->assertIteratorEquals(p.segmentStream().iterator(), p.iterator()))
			))
			.collect(toList());
	}

	@TestFactory
	Iterable<DynamicTest> testOfListOfString() {
		return Stream.of(new String[][] {{}, {"a"}, {"a","b"}, {"a", "-p1-", "b"}})
			.map(ss->dynamicTest("List vs array [" + Arrays.toString(ss) + "]", ()->assertEquals(Path.empty().then(ss), Path.of(asList(ss)))))
			.collect(toList());
	}

	@TestFactory
	Iterable<DynamicTest> testThen() {
		String[] nothing = new String[0];
		String[] a = new String[] { "a" };
		String[] b = new String[] { "b" };
		String[] ab = new String[] { "a", "b" };
		String[] cd = new String[] { "c", "d" };
		String[] slash = new String[] { "a/b" };
		Stream<Stream<DynamicTest>> testStreams = Stream.of(
			thenTests(nothing, nothing),
			thenTests(nothing, a, "a"),
			thenTests(nothing, ab, "a", "b"),
			thenTests(nothing, slash, "a/b"),
			thenTests(a, nothing, "a"),
			thenTests(a, b, "a", "b"),
			thenTests(a, cd, "a", "c", "d"),
			thenTests(a, slash, "a", "a/b"),
			thenTests(ab, nothing, "a", "b"),
			thenTests(cd, a, "c", "d", "a"),
			thenTests(ab, cd, "a", "b", "c", "d"),
			thenTests(cd, slash, "c", "d", "a/b")
		);
		return testStreams.flatMap(identity())
			.collect(toList());
	}

	private Stream<DynamicTest> thenTests(String[] base, String[] then, String...expectedSegments) {
		String[] actualArraySegments = Path.empty().then(base).then(then).segmentStream().toArray(String[]::new);
		String[] actualListSegments  = Path.empty().then(base).then(asList(then)).segmentStream().toArray(String[]::new);
		return Stream.of(
			dynamicTest(format("(%s).then(%s)", Arrays.toString(base), Arrays.toString(then)), ()->assertArrayEquals(expectedSegments, actualArraySegments)),
			dynamicTest(format("(%s).then(asList(%s))", Arrays.toString(base), Arrays.toString(then)), ()->assertArrayEquals(expectedSegments, actualListSegments))
		);
	}

	private static <T> void assertIteratorEquals(Iterator<? super T> expected, Iterator<? super T> actual) {
		while (expected.hasNext()) {
			assertTrue(actual.hasNext());
			assertEquals(expected.next(), actual.next());
		}
		assertFalse(actual.hasNext());
	}

	@TestFactory
	Stream<DynamicTest> testIsPrefixOf() {
		return Stream.of(
			prefixTest(Path.empty(), Path.empty()),
			prefixTest(Path.empty(), Path.just("a")),
			prefixTest(Path.empty(), Path.of("a","b")),
			prefixTest(Path.empty(), Path.just("a/b")),

			notPrefixTest(Path.just("a"), Path.empty()),
			prefixTest(Path.just("a"), Path.just("a")),
			prefixTest(Path.just("a"), Path.of("a","b")),

			notPrefixTest(Path.of("a","b"), Path.empty()),
			notPrefixTest(Path.of("a","b"), Path.just("a")),
			prefixTest(Path.of("a","b"), Path.of("a","b")),
			prefixTest(Path.of("a","b"), Path.of("a","b","c")),
			notPrefixTest(Path.of("a","b"), Path.of("a","c")),

			notPrefixTest(Path.of("a","b","c"), Path.empty()),
			notPrefixTest(Path.of("a","b","c"), Path.just("a")),
			notPrefixTest(Path.of("a","b","c"), Path.of("a","b")),
			prefixTest(Path.of("a","b","c"), Path.of("a","b","c")),
			prefixTest(Path.of("a","b","c"), Path.of("a","b","c","d")),
			notPrefixTest(Path.of("a","b","c"), Path.of("a","b","d")),

			notPrefixTest(Path.of("a","-p1-","c"), Path.empty()),
			notPrefixTest(Path.of("a","-p1-","c"), Path.just("a")),
			notPrefixTest(Path.of("a","-p1-","c"), Path.of("a","-p1-")),
			prefixTest(Path.of("a","-p1-","c"), Path.of("a","-p1-","c")),
			prefixTest(Path.of("a","-p1-","c"), Path.of("a","-p1-","c","d")),
			notPrefixTest(Path.of("a","-p1-","c"), Path.of("a","-p1-","d")),
			notPrefixTest(Path.of("a","-p1-","c"), Path.of("a","b","c")),
			notPrefixTest(Path.of("a","-p1-","c"), Path.of("a","b","c","d")),

			// Some other oddball cases
			notPrefixTest(Path.of("a","b"), Path.just("b")),
			notPrefixTest(Path.just("a"), Path.just("a/b")),
			notPrefixTest(Path.just("a/b"), Path.just("a")),
			notPrefixTest(Path.of("a","b"), Path.just("a/b")),
			notPrefixTest(Path.just("a/b"), Path.of("a","b"))
		);
	}

	private DynamicTest prefixTest(Path prefix, Path path) {
		return dynamicTest("(" + prefix + ").isPrefixOf(" + path + ")", () -> assertTrue(prefix.isPrefixOf(path)));
	}

	private DynamicTest notPrefixTest(Path prefix, Path path) {
		return dynamicTest("!(" + prefix + ").isPrefixOf(" + path + ")", () -> assertFalse(prefix.isPrefixOf(path)));
	}


	@TestFactory
	Stream<DynamicTest> testTruncation() {
		return Stream.of(
			truncationTests(Path.empty(),     Path.empty(),     0),
			truncationTests(Path.just("a"),   Path.just("a"),   0),
			truncationTests(Path.empty(),     Path.just("a"),   1),
			truncationTests(Path.of("a","b"), Path.of("a","b"), 0),
			truncationTests(Path.just("a"),   Path.of("a","b"), 1),
			truncationTests(Path.empty(),     Path.of("a","b"), 2),

			illegalTruncationTests(Path.empty(), 1),
			illegalTruncationTests(Path.of("a","b"), 3),
			illegalTruncationTests(Path.of("a","b"), -1)
		).flatMap(identity());
	}

	private Stream<DynamicTest> truncationTests(Path expected, Path given, int numTruncatedBy) {
		int numTruncatedTo = given.length() - numTruncatedBy;
		return Stream.of(
			dynamicTest("(" + given + ").truncatedBy(" + numTruncatedBy + ")", () -> assertEquals(expected, given.truncatedBy(numTruncatedBy))),
			dynamicTest("(" + given + ").truncatedTo(" + numTruncatedTo + ")", () -> assertEquals(expected, given.truncatedTo(numTruncatedTo)))
		);
	}

	private Stream<DynamicTest> illegalTruncationTests(Path given, int numTruncatedBy) {
		int numTruncatedTo = given.length() - numTruncatedBy;
		return Stream.of(
			dynamicTest("!(" + given + ").truncatedBy(" + numTruncatedBy + ")", () -> assertThrows(IllegalArgumentException.class, () -> given.truncatedBy(numTruncatedBy))),
			dynamicTest("!(" + given + ").truncatedTo(" + numTruncatedTo + ")", () -> assertThrows(IllegalArgumentException.class, () -> given.truncatedTo(numTruncatedTo)))
		);
	}

	@TestFactory
	Iterable<DynamicTest> testSegment() {
		return asList(
			dynamicTest("(a).segment(0)",     () -> assertEquals("a", Path.just("a").segment(0))),
			dynamicTest("(a/b).segment(0)",   () -> assertEquals("a", Path.of("a","b").segment(0))),
			dynamicTest("(a/b).segment(1)",   () -> assertEquals("b", Path.of("a","b").segment(1))),
			dynamicTest("('a/b').segment(0)", () -> assertEquals("a/b", Path.just("a/b").segment(0))),

			dynamicTest("!(a).segment(1)",
				()->assertThrows(IllegalArgumentException.class, () -> Path.just("a").segment(1))),
			dynamicTest("!(a).segment(-1)",
				()->assertThrows(IllegalArgumentException.class, () -> Path.just("a").segment(-1)))
		);
	}

	@TestFactory
	Iterable<DynamicTest> testSegmentStream() {
		return asList(
			dynamicTest("().segmentStream",    () -> assertEquals(emptyList(), Path.empty().segmentStream().collect(toList()))),
			dynamicTest("(a).segmentStream",   () -> assertEquals(singletonList("a"), Path.just("a").segmentStream().collect(toList()))),
			dynamicTest("(a/b).segmentStream", () -> assertEquals(asList("a","b"), Path.of("a","b").segmentStream().collect(toList())))
		);
	}

	@TestFactory
	Iterable<DynamicTest> testLastSegment() {
		return asList(
			dynamicTest("!().lastSegment",    ()->assertThrows(IllegalArgumentException.class, ()-> Path.empty().lastSegment())),
			dynamicTest("(a).lastSegment", () -> assertEquals("a", Path.just("a").lastSegment())),
			dynamicTest("(a/b).lastSegment", ()->assertEquals("b", Path.of("a","b").lastSegment()))
		);
	}

	@TestFactory
	@SuppressWarnings({"SimplifiableAssertion", "EqualsWithItself"})// We're explicitly calling "equals" here, and that's ok
	Iterable<DynamicTest> testEquals() {
		return asList(
			dynamicTest("empty.equals()",         () -> assertTrue(Path.empty().equals(Path.empty().then()))),
			dynamicTest("empty.equals(empty)",    () -> assertTrue(Path.empty().equals(Path.empty()))),
			dynamicTest("(a).equals(a)",          () -> assertTrue(Path.just("a").equals(Path.just("a")))),
			dynamicTest("(a).equals((a/b).truncatedBy(1))", () -> assertTrue(Path.just("a").equals(Path.of("a", "b").truncatedBy(1)))),
			dynamicTest("(a/b).equals(a/b)",      () -> assertTrue(Path.of("a","b").equals(Path.of("a","b")))),
			dynamicTest("(a/-p1-/b).equals(a/-p1-/b)",  () -> assertTrue(Path.of("a","-p1-","b").equals(Path.of("a","-p1-","b")))),

			dynamicTest("!(a/b).equals(a)", () -> assertFalse(Path.of("a","b").equals(Path.just("a")))),
			dynamicTest("!(a).equals(a/b)", () -> assertFalse(Path.just("a").equals(Path.of("a","b")))),
			dynamicTest("!(a/b).equals('a/b')", () -> assertFalse(Path.of("a","b").equals(Path.just("a/b")))),
			dynamicTest("!(a/-p1-).equals('a/b')", () -> assertFalse(Path.of("a","-p1-").equals(Path.just("a/b")))),
			dynamicTest("!(a/-p1-).equals('a/-p2-')", () -> assertFalse(Path.of("a","-p1-").equals(Path.of("a","-p2-"))))
		);
	}

	@TestFactory
	@SuppressWarnings("deprecation") // We're intentionally testing methods that are never meant to be called
	Iterable<DynamicTest> testInvalidOf() {
		return asList(
			dynamicTest("of()",  ()->assertThrows(IllegalArgumentException.class, () -> Path.of())),
			dynamicTest("of(a)", ()->assertThrows(IllegalArgumentException.class, () -> Path.of("a")))
		);
	}

	@TestFactory
	Stream<DynamicTest> testParameters() {
		Stream<DynamicTest> parameterTests = Stream.of(
			parameterTests(1, 0, "-p1-"),
			parameterTests(2, 0, "-p1-", "-p2-"),
			parameterTests(3, 0, "-p1-", "-p2-", "-p3-"),
			parameterTests(1, 3, "a", "b", "c", "-p1-"),
			parameterTests(2, 3, "a", "b", "c", "-p1-", "-p2-"),
			parameterTests(2, 1, "a", "-p1-", "b", "c", "-p2-")
		).flatMap(identity());
		Stream<DynamicTest> oneOffTests = Stream.of(
			dynamicTest("# /a/b",         () -> assertEquals(0, Path.parse("/a/b").numParameters())),
			dynamicTest("# /a/-p-",       () -> assertEquals(1, Path.of("a","-p-").numParameters())),
			dynamicTest("# /a/-p- bound", () -> assertEquals(0, Path.of("a","-p-").boundBy(singletonBinding("p","b")).numParameters()))

		);
		Stream<DynamicTest> sadPath = Stream.of(
			noFirstParameterTest(Path.empty()),
			noFirstParameterTest(Path.just("a")),
			noFirstParameterTest(Path.of("a","b")),
			noFirstParameterTest(Path.of("a","b","-p1-").boundBy(singletonBinding("p1", "c")))
		);
		return Stream.of(parameterTests, oneOffTests, sadPath).flatMap(identity());
	}

	private Stream<DynamicTest> parameterTests(int numParameters, int firstParameterIndex, String... segments) {
		Path path = Path.of(segments);
		return Stream.of(
			dynamicTest("numParameters(" + path + ")", ()->assertEquals(numParameters, path.numParameters())),
			dynamicTest("firstParameterIndex(" + path + ")", ()->assertEquals(firstParameterIndex, path.firstParameterIndex()))
		);
	}

	private DynamicTest noFirstParameterTest(Path path) {
		return dynamicTest("!firstParameterIndex(" + path + ")", () -> assertThrows(IllegalArgumentException.class, () -> path.firstParameterIndex()));
	}

	@TestFactory
	Stream<DynamicTest> testParametersFrom() {
		return Stream.of(
			parametersFromTest("/a", Path.just("-p-"), singletonBinding("p", "a")),
			parametersFromTest("/a/b", Path.just("-p-"), singletonBinding("p", "a")),
			parametersFromTest("/a/b", Path.of("a", "-p-"), singletonBinding("p", "b")),
			parametersFromTest("/a/b", Path.of("-p-", "b"), singletonBinding("p", "a")),
			parametersFromTest("/a", Path.of("-p-", "b"), singletonBinding("p", "a")),
			parametersFromTest("/", Path.of("-p1-", "-p2-"), BindingEnvironment.empty()),
			parametersFromTest("/a", Path.of("-p1-", "-p2-"), singletonBinding("p1", "a")),
			parametersFromTest("/a/b", Path.of("-p1-", "-p2-"), doubletonBinding("p1", "a", "p2", "b")),
			parametersFromTest("/a/b/c/d", Path.of("-p1-", "-p2-"), doubletonBinding("p1", "a", "p2", "b")),
			parametersFromTest("/", Path.empty(), BindingEnvironment.empty()),
			parametersFromTest("/a/b", Path.of("a", "b"), BindingEnvironment.empty()),

			badParametersFromTest("/a", Path.just("b")),
			badParametersFromTest("/a/b", Path.of("b", "-p-"))
		);
	}

	private BindingEnvironment singletonBinding(String name, String value) {
		return BindingEnvironment.singleton(name, Identifier.from(value));
	}

	private BindingEnvironment doubletonBinding(String name1, String value1, String name2, String value2) {
		return BindingEnvironment.empty().builder()
			.bind(name1, Identifier.from(value1))
			.bind(name2, Identifier.from(value2))
			.build();
	}

	private DynamicTest parametersFromTest(String concretePath, Path parameterizedPath, BindingEnvironment expected) {
		return dynamicTest("(" + parameterizedPath + ").parametersFrom(" + concretePath + ")", () ->
			assertEquals(expected, parameterizedPath.parametersFrom(Path.parse(concretePath))));
	}

	private DynamicTest badParametersFromTest(String concretePath, Path parameterizedPath) {
		return dynamicTest("!(" + parameterizedPath + ").parametersFrom(" + concretePath + ")", () ->
			assertThrows(AssertionError.class, () -> parameterizedPath.parametersFrom(Path.parse(concretePath))));
	}

	@TestFactory
	Iterable<DynamicTest> testBoundBy() {
		Path definite = Path.of("a", "b");
		Path indefinite = Path.of("a", "-p1-", "b", "-p2-");
		Identifier id1 = Identifier.from("1");
		Identifier id2 = Identifier.from("2");
		return asList(
			dynamicTest(definite + " ()",      () -> assertEquals(definite, definite.boundBy(BindingEnvironment.empty()))),
			dynamicTest(definite + " (x)",     () -> assertEquals(definite, definite.boundBy(singletonBinding("p1", "x")))),
			dynamicTest(definite + " (y)",     () -> assertEquals(definite, definite.boundBy(singletonBinding("p2", "y")))),
			dynamicTest(definite + " (x,y)",   () -> assertEquals(definite, definite.boundBy(doubletonBinding("p1", "x", "p2", "y")))),
			dynamicTest(indefinite + " ()",    () -> assertEquals(indefinite, indefinite.boundBy(BindingEnvironment.empty()))),
			dynamicTest(indefinite + " (x)",   () -> assertEquals(Path.of("a","x","b","-p2-"), indefinite.boundBy(singletonBinding("p1", "x")))),
			dynamicTest(indefinite + " (x)",   () -> assertEquals(Path.of("a","-p1-","b","y"), indefinite.boundBy(singletonBinding("p2", "y")))),
			dynamicTest(indefinite + " (x,y)", () -> assertEquals(Path.of("a","x","b","y"), indefinite.boundBy(doubletonBinding("p1", "x", "p2", "y"))))
		);
	}

	@TestFactory
	Iterable<DynamicTest> testValidSegment() {
		Stream<DynamicTest> valid = Stream.of("a", "a/b").flatMap(s -> Stream.of(
				dynamicTest("validSegment(" + s + ")", () -> assertEquals(s, Path.validSegment(s))),
				dynamicTest("validParsedSegment(" + s + ")", () -> assertEquals(s, Path.validParsedSegment(s)))
		));
		Stream<DynamicTest> invalid = Stream.of(null, "", "-", "-a", "-123", "-123-", "-💩-").flatMap(s -> Stream.of(
				dynamicTest("!validSegment(" + s + ")", () -> assertThrows(IllegalArgumentException.class, () -> Path.validSegment(s))),
				dynamicTest("!validParsedSegment(" + s + ")", () -> assertThrows(IllegalArgumentException.class, () -> Path.validParsedSegment(s)))
		));
		Stream<DynamicTest> validUnlessParsed = Stream.of("-p1-").flatMap(s -> Stream.of(
				dynamicTest("validSegment(" + s + ")", () -> assertEquals(s, Path.validSegment(s))),
				dynamicTest("!validParsedSegment(" + s + ")", () -> assertThrows(IllegalArgumentException.class, () -> Path.validParsedSegment(s)))
		));
		return Stream.of(valid, invalid, validUnlessParsed).flatMap(identity()).collect(toList());
	}

	@TestFactory
	Stream<DynamicTest> testMatches() {
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
		definite.forEach(p -> matches.put(p, singletonList(p))); // Every path matches itself
		matches.put(a_p1_b,  asList(a_p1_b, a_1_b));
		matches.put(a_p1_b_p2, asList(a_p1_b_p2, a_1_b_p1, a_1_b_p2, a_p1_b_2, a_1_b_2));
		matches.put(a_1_b_p1, asList(a_1_b_p1, a_1_b_p2, a_1_b_2));
		matches.put(a_1_b_p2, asList(a_1_b_p1, a_1_b_p2, a_1_b_2));
		matches.put(a_p1_b_2, asList(a_p1_b_2, a_1_b_2));

		// Exhaustively test all pairs against the expected results
		return all.stream().flatMap(pattern -> all.stream().map(path ->
				matchesTest(pattern, path, matches.get(pattern).contains(path))));
	}

	private DynamicTest matchesTest(Path pattern, Path other, boolean result) {
		return dynamicTest((result? "":"!") + "(" + pattern + ").matches(" + other + ")", () -> assertEquals(result, pattern.matches(other), pattern + " should " + (result? "" : "not ") + "match " + other));
	}

}
