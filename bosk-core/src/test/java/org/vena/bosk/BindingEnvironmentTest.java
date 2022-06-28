package org.vena.bosk;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.vena.bosk.BindingEnvironment.Builder;
import org.vena.bosk.exceptions.ParameterAlreadyBoundException;
import org.vena.bosk.exceptions.ParameterUnboundException;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static java.util.function.Function.identity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

class BindingEnvironmentTest extends AbstractBoskTest {
	@BeforeEach
	void setUp() {
	}

	private static Stream<EnvTest> allEnvTests() {
		BindingEnvironment empty = BindingEnvironment.empty();
		BindingEnvironment p1 = BindingEnvironment.singleton("p1", id(1));
		BindingEnvironment p2 = BindingEnvironment.singleton("p2", id(2));
		BindingEnvironment p1p2 = Builder.empty().bind("p1", id(1)).bind("p2", id(2)).build();
		BindingEnvironment p2p1 = BindingEnvironment.empty().builder().bind("p2", id(2)).bind("p1", id(1)).build();
		BindingEnvironment backward = Builder.empty().bind("p1", id(2)).bind("p2", id(1)).build();

		return Stream.of(
			envTests(emptyMap(), empty),
			envTests(singletonMap("p1", id(1)), p1),
			envTests(singletonMap("p2", id(2)), p2),
			envTests(MapMaker.with("p1", id(1)).and("p2", id(2)).map, p1p2),
			envTests(MapMaker.with("p2", id(2)).and("p1", id(1)).map, p2p1),
			envTests(MapMaker.with("p1", id(2)).and("p2", id(1)).map, backward),

			envTests(emptyMap(),
				p1.builder().unbind("p1").build(),
				p2.builder().unbind("p2").build(),
				p1p2.builder().unbind("p1").unbind("p2").build(),
				p2p1.builder().unbind("p1").unbind("p2").build(),
				BindingEnvironment.Builder.empty().unbind("nonexistent").build()),
			envTests(singletonMap("p1", id(1)),
				empty.builder().bind("p1", id(1)).build(),
				p1.builder().unbind("p2").build(),
				p1p2.builder().unbind("p2").build(),
				p2p1.builder().unbind("p2").build(),
				p2p1.builder().unbind("p2").unbind("p2").build(), // Unbinding twice is ok
				backward.builder()
					.rebind("p1", id(1))
					.unbind("p2").build())
		).flatMap(identity());
	}

	static class MapMaker {
		final Map<String, Identifier> map = new LinkedHashMap<>();

		static MapMaker with(String key, Identifier value) {
			MapMaker result = new MapMaker();
			result.and(key, value);
			return result;
		}

		MapMaker and(String key, Identifier value) {
			map.put(key, value);
			return this;
		}
	}

	private static Stream<EnvTest> envTests(Map<String, Identifier> map, BindingEnvironment... envs) {
		return Stream.of(envs)
			.map(e -> new EnvTest(map, e));
	}

	@Value
	static class EnvTest {
		Map<String, Identifier> map;
		BindingEnvironment env;
	}

	/**
	 * pitest counts a mutation as "surviving" if it causes test initialization to crash,
	 * which is sorta weird. Anyhoo, this test _fails_ (rather than crashing on initialization)
	 * if certain basic operations are broken.
	 */
	@Test
	void testBasicConstruction() {
		assertEquivalent(emptyMap(), BindingEnvironment.empty(), "For pitest");
		assertEquivalent(singletonMap("p", id(1)), BindingEnvironment.singleton("p", id(1)), "For pitest");
		assertEquivalent(singletonMap("p", id(1)), BindingEnvironment.empty().builder().bind("p", id(1)).build(), "For pitest");

		assertThrows(ParameterAlreadyBoundException.class, () -> Builder.empty().bind("p", id(1)).bind("p", id(1)));

		Builder builder = Builder.empty();
		assertSame(builder, builder.bind("p", id(1)), "Fluent bind() should return its receiver object");
		assertSame(builder, builder.rebind("p", id(2)), "Fluent rebind() should return its receiver object");
		assertSame(builder, builder.unbind("p"), "Fluent unbind() should return its receiver object");
	}

	@TestFactory
	Stream<DynamicTest> testMapIsEquivalentToEnvironment() {
		Stream<DynamicTest> happyPaths = allEnvTests()
			.map(t -> dynamicTest(t + " equivalence", ()->assertEquivalent(t.map, t.env, "Map should be equivalent to environment")));
		Stream<DynamicTest> sadPaths = allEnvTests().map(t ->
			dynamicTest(t.map + ".get(nonexistent)", () -> assertThrows(ParameterUnboundException.class, () -> t.env.get("nonexistent"))));
		return Stream.concat(happyPaths, sadPaths);
	}

	@TestFactory
	Stream<DynamicTest> testEquals() {
		return allEnvTests().map(outer -> dynamicTest(outer.map + " equals on everything", () -> {
			allEnvTests().forEachOrdered(inner -> {
				assertEquals(outer.map.equals(inner.map), outer.env.equals(inner.env));
			});
		}));
	}

	@TestFactory
	Stream<DynamicTest> testOverlay() {
		return Stream.concat(
			allEnvTests().map(t -> dynamicTest(t.map + " overlay itself", () ->
				assertEquivalent(t.map, t.env.overlay(t.env), "Overlaying an environment with itself should have no effect"))),
			allEnvTests().map(outer -> dynamicTest(outer.map + ".overlay everything", () ->
				allEnvTests().forEachOrdered(inner -> assertOverlayActsLikePutAll(outer, inner))))
		);
	}

	private void assertOverlayActsLikePutAll(EnvTest left, EnvTest right) {
		LinkedHashMap<String, Identifier> map1 = new LinkedHashMap<>(left.map);
		map1.putAll(right.map);
		assertEquivalent(map1, left.env.overlay(right.env), "Overlay should act like putAll");
	}

	void assertEquivalent(Map<String, Identifier> expected, BindingEnvironment actual, String message) {
		assertEquals(expected, actual.asMap(), message + ": asMap should return the correct value");
		expected.forEach((name, value) -> {
			assertEquals(value, actual.get(name), message + ": get should return the correct value");
			assertEquals(value, actual.getOrDefault(name, NONEXISTENT), message + ": getOrDefault should return the correct value");
			assertEquals(value, actual.getForParameterSegment("-" + name + "-"), message + ": getForParameterSegment should return the correct value");
			assertThrows(IllegalArgumentException.class, () -> actual.get("-" + name), message + ": get should throw with name starting with hyphen");
			assertThrows(IllegalArgumentException.class, () -> actual.get("1" + name), message + ": get should throw with name starting with digit");
		});
		Iterator<String> nameIter = expected.keySet().iterator();
		actual.forEach((name, value) -> {
			assertEquals(value, expected.getOrDefault(name, NONEXISTENT), message + ": forEach should encounter only the expected bindings");
			assertEquals(nameIter.next(), name, message + ": forEach should encounter the bindings in the expected order");
		});
	}

	static Identifier id(int i) {
		return Identifier.from("id" + i);
	}

	/**
	 * A special Identifier that can't possibly be equal to any other Identifier.
	 */
	private static final Identifier NONEXISTENT = Identifier.from("Nonexistent-" + UUID.randomUUID().toString());
}
