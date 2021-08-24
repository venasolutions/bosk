package org.vena.bosk;

import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.WeakHashMap;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.vena.bosk.BindingEnvironment.Builder;
import org.vena.bosk.exceptions.MalformedPathException;

import static java.lang.Character.isDigit;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static lombok.AccessLevel.PACKAGE;

/**
 * Represents a sequence of steps from one object to another object via fields.
 *
 * <p>
 * Invalid paths (meaning paths that don't actually point to anything) are
 * explicitly allowed.  Validity is checked when a Path is turned into a {@link
 * Reference} via methods such as {@link Bosk#reference(Class, Path)
 * Bosk.reference}.
 *
 * <p>
 * Path objects are interned and reused. If you implement a {@link
 * ReflectiveEntity} that holds a {@link Reference} to itself, that will be
 * sufficient to make the entity's Path a suitable key for a {@link WeakHashMap}
 * to hold local state for that object (because {@link Reference}s contain Path
 * objects.) TODO: Seems like if you make a mistake on this, you're in for a bug
 * that's very hard to reproduce and diagnose. We need a better story for
 * managing local state.
 *
 * @author pdoyle
 */
@Accessors(fluent=true)
@EqualsAndHashCode
@RequiredArgsConstructor(access = PACKAGE)
public abstract class Path implements Iterable<String> {
	public abstract int length();

	public final boolean isEmpty() { return length() == 0; }

	/**
	 * @param urlEncoded A string representation of the path with the segments
	 * URLEncoded and separated by slashes.
	 *
	 * @return A Path with one segment for each (possibly empty) string before,
	 * between, and after the slashes.  As a special case, for a blank string,
	 * we return {@link Path#empty()}.  (Otherwise, a blank string would refer
	 * to the illegal path with a single blank segment.)
	 *
	 * @throws MalformedPathException if the given string contains any
	 * paths that are invalid according to {@link #validParsedSegment(String)}.
	 */
	public static Path parse(String urlEncoded) {
		if (urlEncoded.isEmpty()) {
			return Path.empty();
		} else {
			return Path.of(Stream.of(urlEncoded.split("/", Integer.MAX_VALUE))
					.map(DECODER)
					.map(Path::validParsedSegment)
					.collect(toList()));
		}
	}

	/**
	 * Like {@link #parse} but permits parameter segments.
	 */
	public static Path parseParameterized(String urlEncoded) {
		if (urlEncoded.isEmpty()) {
			return Path.empty();
		} else {
			return Path.of(Stream.of(urlEncoded.split("/", Integer.MAX_VALUE))
				.map(DECODER)
				.map(Path::validSegment)
				.collect(toList()));
		}
	}

	public final String urlEncoded() {
		return segmentStream()
			.map(ENCODER)
			.collect(joining("/"));
	}

	/**
	 * @return Path with no segments
	 */
	public static Path empty() {
		return ROOT_PATH;
	}

	/**
	 * @return Path with just one segment
	 */
	public static Path just(String segment) {
		return ROOT_PATH.then(segment);
	}

	/**
	 * Build a path out of the given segments.
	 */
	public static Path of(String... segments) {
		return ROOT_PATH.then(segments);
	}

	/**
	 * @deprecated Call {@link #just} if you want a path with one segment. Call {@link #parse} if you
	 * want to process a full URL-encoded path string.
	 */
	@Deprecated
	@SuppressWarnings("unused")
	public static Path of(String segment) {
		throw new IllegalArgumentException("Use Path.just when you have exactly one path segment");
	}

	/**
	 * @deprecated Call {@link #empty} if you want an empty Path.
	 */
	@Deprecated
	public static Path of() {
		throw new IllegalArgumentException("Use Path.empty when you have no path segments");
	}

	public static Path of(List<String> segments) {
		return ROOT_PATH.then(segments);
	}

	public final Path then(String... segments) {
		return this.then(asList(segments));
	}

	/**
	 * @throws MalformedPathException if <code>segments</code> contains an invalid path segment.
	 */
	public final Path then(List<String> segments) {
		if (segments.size() == 0) {
			return this;
		} else {
			String firstSegment = validSegment(segments.get(0));
			if (isParameterSegment(firstSegment)) {
				if (segmentStream().anyMatch(firstSegment::equals)) {
					throw new MalformedPathException("Duplicate path parameter \"" + firstSegment + "\"");
				}
			}
			List<String> remainder = segments.subList(1, segments.size());
			return INTERNER.apply(new NestedPath(this, firstSegment)).then(remainder);
		}
	}

	/**
	 * @return <code>str</code> if a valid segment; otherwise, throws {@link MalformedPathException}.
	 * @throws MalformedPathException if <code>str</code> is not a valid path segment.
	 */
	public static String validSegment(String str) {
		if (str == null) {
			throw new MalformedPathException("Segment cannot be null");
		} else if (str.isEmpty()) {
			throw new MalformedPathException("Segment cannot be blank");
		} else if (str.startsWith("-")) {
			if (isParameterSegment(str)) {
				return str;
			} else {
				throw new MalformedPathException("Segment starting with \"-\" must be a valid parameter");
			}
		} else {
			return str;
		}
	}

	public final boolean isPrefixOf(Path other) {
		int excessSegments = other.length() - this.length();
		if (excessSegments >= 0) {
			return this.equals(other.truncatedBy(excessSegments));
		} else {
			return false;
		}
	}

	public final Path truncatedBy(int droppedSegments) {
		if (droppedSegments < 0) {
			throw new IllegalArgumentException("Negative number of segments to drop: " + droppedSegments);
		} else if (droppedSegments == 0) {
			return this;
		} else if (droppedSegments > length()) {
			throw new IllegalArgumentException("Cannot truncate " + droppedSegments + " segments from path of length " + length() + ": " + this);
		} else {
			return truncatedByImpl(droppedSegments);
		}
	}

	public final Path truncatedTo(int remainingSegments) {
		return truncatedBy(length() - remainingSegments);
	}

	/**
	 * @return true if there exists a hypothetical binding environment in which
	 * this path equals <code>other</code>. (I don't say "if and only if" because
	 * I suspect SAT could be reduced to that, making it NP-complete. -pdoyle)
	 */
	public final boolean matches(Path other) {
		if (this == other) {
			return true;
		} else if (this.length() == other.length()) {
			return matchesImpl(other.truncatedBy(other.length() - this.length()));
		} else {
			return false;
		}
	}

	public final String segment(int index) {
		return this.truncatedBy(length()-1-index).lastSegment();
	}

	/**
	 * @return the rightmost segment
	 * @throws IllegalArgumentException if {@link #isEmpty()}
	 */
	public abstract String lastSegment();

	public abstract int numParameters();
	public abstract int firstParameterIndex();

	@Override
	public final String toString() {
		return urlEncoded();
	}

	@Override
	public final Iterator<String> iterator() {
		return segmentStream().iterator();
	}

	public final Stream<String> segmentStream() {
		Stream.Builder<String> builder = Stream.builder();
		addSegmentsTo(builder);
		return builder.build();
	}

	protected abstract void addSegmentsTo(Stream.Builder<String> builder);
	protected abstract Path truncatedByImpl(int n);
	protected abstract boolean matchesImpl(Path other);

	public final BindingEnvironment parametersFrom(Path definitePath) {
		assert definitePath.numParameters() == 0: "Parameter " + definitePath.segment(definitePath.firstParameterIndex()) + " must be bound";
		int commonLength = Math.min(length(), definitePath.length());
		Path thisTruncated = this.truncatedTo(commonLength);
		Path definitePathTruncated = definitePath.truncatedTo(commonLength);
		assert thisTruncated.matches(definitePathTruncated): "Path mismatch: " + this + " vs " + definitePath;

		BindingEnvironment.Builder result = BindingEnvironment.empty().builder();
		thisTruncated.addParametersTo(result, definitePathTruncated);
		return result.build();
	}

	/**
	 * Requires <code>definitePath.length()</code> equals {@link #length() this.length()}.
	 */
	protected abstract void addParametersTo(Builder builder, Path definitePath);

	/**
	 * @param bindings provides values for zero or more parameters. <code>bindings</code> may leave some parameters unbound, and may provide bindings for names not present in this Path.
	 * @return {@link Path} with parameters substituted for the IDs provided in <code>bindings</code> if any
	 */
	public abstract Path boundBy(BindingEnvironment bindings);

	/**
	 * @return a BindingEnvironment mapping the parameters in this path, in order, to the given <code>ids</code>.
	 * If this path has excess parameters, they have no effect on the result.
	 * @throws IllegalArgumentException if this path has fewer parameters than <code>ids.size()</code>
	 */
	public final BindingEnvironment parametersFrom(Collection<Identifier> ids) {
		Iterator<String> parameterIter = segmentStream().filter(Path::isParameterSegment).iterator();
		BindingEnvironment.Builder env = BindingEnvironment.empty().builder();
		try {
			for (Identifier id: ids) {
				env.bind(parameterNameFromSegment(parameterIter.next()), id);
			}
		} catch (NoSuchElementException e) {
			throw new MalformedPathException("Path has fewer than " + ids.size() + " parameters: " + this, e);
		}
		return env.build();
	}

	private static final UnaryOperator<String> DECODER;
	private static final UnaryOperator<String> ENCODER;

	/*
	 * Eclipse has an annoying bug: if I put these lambdas right in the declarations of ENCODER and DECODER, Eclipse keeps deleting the semicolons.
	 */
	static {
		DECODER = s->{
			try {
				return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
			} catch (UnsupportedEncodingException e) {
				throw new AssertionError(e);
			}
		};

		ENCODER = s->{
			// Wow, this code sucks.
			// Unbelievably, URLEncoder does not encode URLs. It's for HTML forms.
			// Somehow, this means we must manually turn '+' characters into '%20'.
			// This does not technically make the result equivalent to actual URL
			// encoding, but the differences (like normalization of newlines)
			// seem either desirable or irrelevant.
			try {
				String formEncoded = URLEncoder.encode(s, StandardCharsets.UTF_8.name());
				return formEncoded.replace("+", "%20");
			} catch (UnsupportedEncodingException e) {
				throw new AssertionError(e);
			}
		};
	}

	/**
	 * Parsing has slightly stronger rules than Paths created programmatically from separate segment strings.
	 */
	public static String validParsedSegment(String segment) {
		if (isParameterSegment(segment)) {
			throw new MalformedPathException("Parameter segment not permitted in parsed path string: \"" + segment + "\"");
		} else {
			return validSegment(segment);
		}
	}

	public static String parameterNameFromSegment(String segment) {
		if (isParameterSegment(segment)) {
			return segment.substring(1, segment.length()-1);
		} else {
			throw new MalformedPathException("Not a valid parameter segment: \"" + segment + "\"");
		}
	}

	public static boolean isParameterSegment(String segment) {
		if (segment == null || segment.length() <= 2) {
			return false;
		}
		if (segment.startsWith("-") && segment.endsWith("-")) {
			// Note: can't call parameterNameForSegment here - infinite recursion
			return isValidParameterName(segment.substring(1, segment.length() - 1));
		} else {
			return false;
		}
	}

	public static boolean isValidParameterName(String name) {
		if (name.length() == 0) {
			return false;
		} else if (!isValidFirstCharacter(name.codePointAt(0))) {
			return false;
		}
		for (int i = 1; i < name.length(); i++) {
			if (!isValidSubsequentCharacter(name.codePointAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isValidFirstCharacter(int ch) {
		// A subset of URL encoding "unreserved characters"
		return ('A' <= ch && ch <= 'Z')
			|| ('a' <= ch && ch <= 'z');
	}

	private static boolean isValidSubsequentCharacter(int ch) {
		return isValidFirstCharacter(ch)
			|| isDigit(ch)
			|| (ch == '_');
	}

	@RequiredArgsConstructor
	@EqualsAndHashCode(callSuper=true)
	private static final class NestedPath extends Path {
		private final Path prefix;
		private final String segment;

		@Override public int length() { return 1 + prefix.length(); }
		@Override public String lastSegment() { return segment; }

		@Override
		public int numParameters() {
			return prefix.numParameters() + ((isParameterSegment(segment))? 1 : 0);
		}

		@Override
		public int firstParameterIndex() {
			// This is a cumbersome little method. It walks the whole linked list multiple times.
			// Not super elegant.
			if (prefix.numParameters() >= 1) {
				return prefix.firstParameterIndex();
			} else if (isParameterSegment(segment)) {
				return prefix.length();
			} else {
				throw new IllegalArgumentException("Path has no parameters");
			}
		}

		@Override
		public Path boundBy(BindingEnvironment bindings) {
			Path newPrefix = prefix.boundBy(bindings);
			if (isParameterSegment(segment)) {
				Identifier id = bindings.getOrDefault(parameterNameFromSegment(segment), null);
				if (id != null) {
					return newPrefix.then(id.toString());
				}
			}
			return newPrefix.then(segment);
		}

		@Override
		protected void addSegmentsTo(Stream.Builder<String> builder) {
			prefix.addSegmentsTo(builder);
			builder.add(segment);
		}

		@Override
		protected void addParametersTo(Builder builder, Path definitePath) {
			assert length() == definitePath.length();
			prefix.addParametersTo(builder, definitePath.truncatedBy(1));
			if (isParameterSegment(segment)) {
				builder.bind(parameterNameFromSegment(segment), Identifier.from(definitePath.lastSegment()));
			}
		}

		@Override
		protected Path truncatedByImpl(int n) {
			if (n == 0) {
				return this;
			} else {
				return prefix.truncatedByImpl(n-1);
			}
		}

		@Override
		protected boolean matchesImpl(Path other) {
			assert this.length() == other.length();
			if (this == other) {
				return true;
			} else if (lastSegmentMatches(other.lastSegment())) {
				return prefix.matchesImpl(other.truncatedBy(1));
			} else {
				return false;
			}
		}

		private boolean lastSegmentMatches(String otherSegment) {
			if (lastSegment().equals(otherSegment)) {
				// Either both are the same parameter, or they are two equal non-parameter strings
				return true;
			} else {
				// If our segment is a parameter, the other one doesn't matter:
				// by appropriate choice of binding, we could make them equal.
				return isParameterSegment(lastSegment());
			}
		}
	}

	@RequiredArgsConstructor
	@EqualsAndHashCode(callSuper=true)
	private static final class RootPath extends Path {
		@Override public int length() { return 0; }

		@Override protected void addSegmentsTo(Stream.Builder<String> builder) { }
		@Override protected void addParametersTo(Builder builder, Path definitePath) { assert definitePath.isEmpty(); }

		@Override
		public int numParameters() {
			return 0;
		}

		@Override
		public int firstParameterIndex() {
			throw new IllegalArgumentException("Path has no parameters");
		}

		@Override
		public Path boundBy(BindingEnvironment bindings) {
			return this;
		}

		@Override
		public String lastSegment() {
			throw new IllegalArgumentException("Root path has no lastSegment");
		}

		@Override
		protected Path truncatedByImpl(int n) {
			assert n == 0: "Should never be called with any value of n other than 0";
			return this;
		}

		@Override
		protected boolean matchesImpl(Path other) {
			assert other.isEmpty(): "Should never be called with a path of a different length";
			return true;
		}
	}

	private static final Path ROOT_PATH = new RootPath();

	private static final Interner<Path> INTERNER = new Interner<>();

	static final class Interner<T> implements UnaryOperator<T> {
		@Override
		public synchronized T apply(T given) {
			WeakReference<T> ref = INTERNED.get(given);
			T existing;
			if (ref == null || (existing = ref.get()) == null) {
				INTERNED.put(given, new WeakReference<>(given));
				return given;
			} else {
				return existing;
			}
		}

		private final Map<T, WeakReference<T>> INTERNED = new WeakHashMap<>();
	}

}
