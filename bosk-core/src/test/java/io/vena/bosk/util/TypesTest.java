package io.vena.bosk.util;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static io.vena.bosk.util.Types.parameterizedType;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypesTest {

	private static Stream<Arguments> cases() {
		return Stream.of(
				args(new TypeToken<List<String>>(){}, parameterizedType(List.class, String.class)),
				args(new TypeToken<List<List<String>>>(){}, parameterizedType(List.class, parameterizedType(List.class, String.class))),
				args(new TypeToken<List<List<List<String>>>>(){}, parameterizedType(List.class, parameterizedType(List.class, parameterizedType(List.class, String.class)))),
				args(new TypeToken<Map<String, List<String>>>(){}, parameterizedType(Map.class, String.class, parameterizedType(List.class, String.class))),
				args(new TypeToken<InnerParameterizedType<String>>(){}, parameterizedType(InnerParameterizedType.class, String.class))
		);
	}

	private static Arguments args(TypeToken<?> token, ParameterizedType actual) {
		return Arguments.of(token.getType(), actual);
	}

	@ParameterizedTest
	@MethodSource("cases")
	void testProperties(ParameterizedType expected, ParameterizedType actual) {
		assertEquals(expected, actual);
		assertEquals(expected.getRawType(), actual.getRawType());
		assertEquals(expected.getOwnerType(), actual.getOwnerType());
		assertArrayEquals(expected.getActualTypeArguments(), actual.getActualTypeArguments());
		assertEqualsWorks(expected, actual);
		assertEquals(expected.hashCode(), actual.hashCode());
	}

	@Test
	void testEquals_list() {
		checkWithVandalism(
				testType(null, List.class, String.class),
				parameterizedType(List.class, String.class)
		);
	}

	@Test
	void testEquals_nestedList() {
		checkWithVandalism(
				testType(null, List.class, testType(null, List.class, String.class)),
				parameterizedType(List.class, parameterizedType(List.class, String.class))
		);
	}

	@Test
	void testEquals_innerClass() {
		checkWithVandalism(
				testType(TypesTest.class, InnerParameterizedType.class, String.class),
				parameterizedType(InnerParameterizedType.class, String.class));
	}

	/**
	 * Check that {@link ParameterizedType#equals} fails when various changes are made
	 * to the other object.
	 */
	private void checkWithVandalism(ParameterizedType expected, ParameterizedType actual) {
		assertEqualsWorks(expected, actual);
		assertEqualsWorks(
				testType(expected.getOwnerType(), expected.getRawType(), expected.getActualTypeArguments()),
				actual);

		assertNotEqualsWorks("Not a type", actual);
		assertNotEqualsWorks(String.class, actual); // Not a parameterized type

		assertNotEqualsWorks(
				testType(String.class, expected.getRawType(), expected.getActualTypeArguments()),
				actual);
		assertNotEqualsWorks(
				testType(expected.getOwnerType(), String.class, expected.getActualTypeArguments()),
				actual);
		assertNotEqualsWorks(
				testType(expected.getOwnerType(), String.class),
				actual);

		// Add another type to the array
		int expectedLength = expected.getActualTypeArguments().length;
		Type[] extended = new Type[expectedLength + 1];
		System.arraycopy(expected.getActualTypeArguments(), 0, extended, 0, expectedLength);
		extended[expectedLength] = String.class;

		assertNotEqualsWorks(
				testType(expected.getOwnerType(), expected.getRawType(), extended),
				actual);
	}

	private void assertEqualsWorks(Object expected, Object actual) {
		// We don't want to call assertEquals here because we are resting
		// the equals method itself, so we need to maintain control over
		// the precise call we're making.
		boolean result = actual.equals(expected);
		assertTrue(result);
	}

	private void assertNotEqualsWorks(Object expected, Object actual) {
		boolean result = actual.equals(expected);
		assertFalse(result);
	}

	@SuppressWarnings("unused")
	interface InnerParameterizedType<T> { }

	static ParameterizedType testType(Type ownerType, Type rawType, Type... actualTypeArguments) {
		return new ParameterizedType() {
			@Override public Type getRawType() { return rawType; }
			@Override public Type getOwnerType() { return ownerType; }
			@Override public Type[] getActualTypeArguments() { return actualTypeArguments; }
		};
	}
}
