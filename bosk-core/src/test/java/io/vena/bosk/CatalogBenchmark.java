package io.vena.bosk;

import io.vena.bosk.AbstractBoskTest.TestEntity;
import io.vena.bosk.AbstractBoskTest.TestRoot;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.util.LinkedHashMap;
import java.util.stream.IntStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import static io.vena.bosk.AbstractBoskTest.TestEnum.OK;
import static org.openjdk.jmh.annotations.Mode.Throughput;

@Fork(0)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class CatalogBenchmark {

	@State(Scope.Benchmark)
	public static class BenchmarkState {
		private Catalog<TestEntity> catalog;
		private LinkedHashMap<Identifier, TestEntity> map;
		private TestEntity newEntity;

		@Setup(Level.Trial)
		public void setup() throws InvalidTypeException {
			Bosk<TestRoot> bosk = new Bosk<TestRoot>(
				"CatalogBenchmarkBosk",
				TestRoot.class,
				AbstractBoskTest::initialRoot,
				Bosk::simpleDriver
			);
			TestEntityBuilder teb = new TestEntityBuilder(bosk);
			int initialSize = 100_000;
			catalog = Catalog.of(IntStream.rangeClosed(1, initialSize).mapToObj(i ->
				teb.blankEntity(Identifier.from("Entity_" + i), OK)));
			map = new LinkedHashMap<>();
			catalog.forEach(e -> map.put(e.id(), e));
			newEntity = teb.blankEntity(Identifier.from("New entity"), OK);
		}
	}

	@Benchmark
	@BenchmarkMode(Throughput)
	public Object catalogWith_sameEntity(BenchmarkState state) {
		return state.catalog.with(state.newEntity);
	}

	@Benchmark
	@BenchmarkMode(Throughput)
	public Object newLinkedHashMap_sameEntity(BenchmarkState state) {
		LinkedHashMap<Identifier, TestEntity> result = new LinkedHashMap<>(state.map);
		result.put(state.newEntity.id(), state.newEntity);
		return result;
	}

	@Benchmark
	@BenchmarkMode(Throughput)
	public Object linkedHashMapPut_sameEntity(BenchmarkState state) {
		return state.map.put(state.newEntity.id(), state.newEntity);
	}

}
