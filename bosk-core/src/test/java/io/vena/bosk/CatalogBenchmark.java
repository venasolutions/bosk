package io.vena.bosk;

import io.vena.bosk.AbstractBoskTest.TestEntity;
import io.vena.bosk.AbstractBoskTest.TestRoot;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.junit.PerformanceTest;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;

import static io.vena.bosk.AbstractBoskTest.TestEnum.OK;
import static io.vena.bosk.MicroBenchmark.callingMethodInfo;

public class CatalogBenchmark {
	private TestEntityBuilder teb;

	@BeforeEach
	void setup() throws InvalidTypeException {
		Bosk<TestRoot> bosk = new Bosk<TestRoot>(
			"CatalogBenchmarkBosk",
			TestRoot.class,
			AbstractBoskTest::initialRoot,
			Bosk::simpleDriver
		);
		teb = new TestEntityBuilder(bosk);
	}

	@PerformanceTest
	void performanceTest_with() {
		int initialSize = 100_000;
		Catalog<TestEntity> catalog = Catalog.of(IntStream.rangeClosed(1, initialSize).mapToObj(i ->
			teb.blankEntity(Identifier.from("Entity_" + i), OK)));
		TestEntity newEntity = teb.blankEntity(Identifier.from("New entity"), OK);
		new MicroBenchmark(callingMethodInfo()) {
			@Override
			protected void doIterations(long count) {
				for (long i = 0; i < count; i++) {
					escape = catalog.with(newEntity);
				}
			}
		}.computeRate();
	}

	public static Object escape;

}
