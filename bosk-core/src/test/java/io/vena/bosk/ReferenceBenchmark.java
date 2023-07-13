package io.vena.bosk;

import io.vena.bosk.exceptions.InvalidTypeException;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

@Fork(0)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@OutputTimeUnit(NANOSECONDS)
public class ReferenceBenchmark extends AbstractBoskTest {

	@State(Scope.Benchmark)
	public static class BenchmarkState {
		private Bosk<TestRoot> bosk;
		private Bosk<TestRoot>.ReadContext context;
		private Reference<TestRoot> rootRef;
		private Reference<TestEnum> ref5Segments;
		private TestRoot root;
		private ThreadLocal<TestRoot> threadLocalRoot;

		final Identifier parentID = Identifier.from("parent");
		final Identifier child1ID = Identifier.from("child1");

		@Setup(Level.Trial)
		public void setup() throws InvalidTypeException {
			this.bosk = setUpBosk(Bosk::simpleDriver);
			context = bosk.readContext();
			rootRef = bosk.rootReference();
			TestRoot localRoot = root = rootRef.value();
			threadLocalRoot = ThreadLocal.withInitial(() -> localRoot);
			ref5Segments = bosk.rootReference().then(TestEnum.class, Path.of(
				TestRoot.Fields.entities, "parent",
				TestEntity.Fields.children, "child1",
				TestChild.Fields.testEnum
			));
		}

		@TearDown(Level.Trial)
		public void closeReadContext() {
			context.close();
		}

	}

	@Benchmark
	@BenchmarkMode(AverageTime)
	public Object benchmarkOverhead(BenchmarkState benchmarkState) {
		return benchmarkState;
	}

	@Benchmark
	@BenchmarkMode(AverageTime)
	public Object root_reused(BenchmarkState benchmarkState) {
		return benchmarkState.rootRef.value();
	}

	@Benchmark
	@BenchmarkMode(AverageTime)
	public Object root_fresh(BenchmarkState benchmarkState) {
		return benchmarkState.bosk.rootReference().value();
	}

	@Benchmark
	@BenchmarkMode(AverageTime)
	public Object deep_reused(BenchmarkState benchmarkState) throws InvalidTypeException {
		return benchmarkState.ref5Segments.value();
	}

	@Benchmark
	@BenchmarkMode(AverageTime)
	public Object deep_reusedRoot(BenchmarkState benchmarkState) throws InvalidTypeException {
		return benchmarkState.rootRef.value()
			.entities()
			.get(benchmarkState.parentID)
			.children()
			.get(benchmarkState.child1ID)
			.testEnum();
	}

	@Benchmark
	@BenchmarkMode(AverageTime)
	public Object deep_java_threadLocal(BenchmarkState benchmarkState) {
		return benchmarkState
			.threadLocalRoot.get()
			.entities()
			.get(benchmarkState.parentID)
			.children()
			.get(benchmarkState.child1ID)
			.testEnum();
	}

	@Benchmark
	@BenchmarkMode(AverageTime)
	public Object deep_java_only(BenchmarkState benchmarkState) {
		return benchmarkState
			.root
			.entities()
			.get(benchmarkState.parentID)
			.children()
			.get(benchmarkState.child1ID)
			.testEnum();
	}
}
