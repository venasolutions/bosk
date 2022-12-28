package io.vena.bosk;

import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.junit.PerformanceTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static io.vena.bosk.MicroBenchmark.callingMethodInfo;

public class ReferenceBenchmark extends AbstractBoskTest {
	private Bosk<TestRoot> bosk;
	private Bosk<TestRoot>.ReadContext context;

	@BeforeEach
	void setup() {
		this.bosk = setUpBosk(Bosk::simpleDriver);
		context = bosk.readContext();
	}

	@AfterEach
	void closeReadContext() {
		context.close();
	}

	@PerformanceTest
	void referencePerf_emptyBenchmark() {
		double rate = new MicroBenchmark(callingMethodInfo()) {
			@Override
			protected void doIterations(long count) {
				for (long i = 0; i < count; i++) {
				}
			}
		}.computeRate();
	}

	@PerformanceTest
	void referencePerf_reused_root() {
		Reference<TestRoot> rootRef = bosk.rootReference();
		double rate = new MicroBenchmark(callingMethodInfo()) {
			@Override
			protected void doIterations(long count) {
				for (long i = 0; i < count; i++) {
					rootRef.value();
				}
			}
		}.computeRate();
	}

	@PerformanceTest
	void referencePerf_fresh_root() {
		double rate = new MicroBenchmark(callingMethodInfo()) {
			@Override
			protected void doIterations(long count) {
				for (long i = 0; i < count; i++) {
					bosk.rootReference().value();
				}
			}
		}.computeRate();
	}

	@PerformanceTest
	void referencePerf_reused_5segments() throws InvalidTypeException {
		Reference<TestEnum> ref = bosk.reference(TestEnum.class, Path.of(
			TestRoot.Fields.entities, "parent",
			TestEntity.Fields.children, "child1",
			TestChild.Fields.testEnum
		));
		double rate = new MicroBenchmark(callingMethodInfo()) {
			public TestEnum escape;

			@Override
			protected void doIterations(long count) {
				for (long i = 0; i < count; i++) {
					escape = ref.value();
				}
			}
		}.computeRate();
	}

	@PerformanceTest
	void referencePerf_javaOnly_5segments() {
		Identifier parentID = Identifier.from("parent");
		Identifier child1ID = Identifier.from("child1");
		ThreadLocal<TestRoot> root = ThreadLocal.withInitial(bosk.rootReference()::value);
		double rate = new MicroBenchmark(callingMethodInfo()) {
			public TestEnum escape;

			@Override
			protected void doIterations(long count) {
				for (long i = 0; i < count; i++) {
					escape = root.get().entities().get(parentID).children().get(child1ID).testEnum();
				}
			}
		}.computeRate();
	}

	@PerformanceTest
	void referencePerf_javaObjectsOnly_5segments() {
		Identifier parentID = Identifier.from("parent");
		Identifier child1ID = Identifier.from("child1");
		TestRoot root = bosk.rootReference().value();
		double rate = new MicroBenchmark(callingMethodInfo()) {
			public TestEnum escape;

			@Override
			protected void doIterations(long count) {
				for (long i = 0; i < count; i++) {
					escape = root.entities().get(parentID).children().get(child1ID).testEnum();
				}
			}
		}.computeRate();
	}
}
