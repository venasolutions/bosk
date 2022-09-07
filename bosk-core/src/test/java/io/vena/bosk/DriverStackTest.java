package io.vena.bosk;

import io.vena.bosk.AbstractBoskTest.TestEntity;
import io.vena.bosk.drivers.ForwardingDriver;
import org.junit.jupiter.api.Test;

import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DriverStackTest {
	final BoskDriver<TestEntity> baseDriver = new ForwardingDriver<>(emptySet());

	@Test
	void emptyStack_returnsDownstream() {
		BoskDriver<TestEntity> actual = DriverStack.<TestEntity>of().build(null, baseDriver);
		assertSame(baseDriver, actual);
	}

	@Test
	void stackedDrivers_correctOrder() {
		DriverStack<TestEntity> stack = DriverStack.of(
			(b,d) -> new TestDriver<>("first", d),
			(b,d) -> new TestDriver<>("second", d)
		);

		TestDriver<TestEntity> firstDriver = (TestDriver<TestEntity>) stack.build(null, baseDriver);
		TestDriver<TestEntity> secondDriver = (TestDriver<TestEntity>) firstDriver.downstream;
		BoskDriver<TestEntity> thirdDriver = secondDriver.downstream;

		assertEquals("first", firstDriver.name);
		assertEquals("second", secondDriver.name);
		assertSame(baseDriver, thirdDriver);
	}

	static class TestDriver<R extends Entity> extends ForwardingDriver<R> {
		final String name;
		final BoskDriver<R> downstream;

		public TestDriver(String name, BoskDriver<R> downstream) {
			super(singletonList(downstream));
			this.name = name;
			this.downstream = downstream;
		}

		@Override
		public String toString() {
			return name;
		}
	}
}
