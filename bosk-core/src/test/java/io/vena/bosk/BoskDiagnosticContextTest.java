package io.vena.bosk;

import io.vena.bosk.annotations.ReferencePath;
import io.vena.bosk.drivers.AbstractDriverTest;
import io.vena.bosk.drivers.state.TestEntity;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.var;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Note that context propagation for driver operations is tested by {@link io.vena.bosk.drivers.DriverConformanceTest}.
 */
class BoskDiagnosticContextTest extends AbstractDriverTest {
	Refs refs;

	public interface Refs {
		@ReferencePath("/string") Reference<String> string();
	}

	@BeforeEach
	void setupBosk() throws InvalidTypeException {
		bosk = new Bosk<TestEntity>(
			BoskDiagnosticContextTest.class.getSimpleName(),
			TestEntity.class,
			AbstractDriverTest::initialRoot,
			Bosk::simpleDriver
		);
		refs = bosk.buildReferences(Refs.class);
	}

	@Test
	void hookRegistration_propagatesDiagnosticContext() throws IOException, InterruptedException {
		Semaphore diagnosticsVerified = new Semaphore(0);
		bosk.driver().flush();
		try (var __ = bosk.diagnosticContext().withAttribute("attributeName", "attributeValue")) {
			bosk.registerHook("contextPropagatesToHook", bosk.rootReference(), ref -> {
				assertEquals("attributeValue", bosk.diagnosticContext().getAttribute("attributeName"));
				assertEquals(MapValue.singleton("attributeName", "attributeValue"), bosk.diagnosticContext().getAttributes());
				diagnosticsVerified.release();
			});
		}
		bosk.driver().flush();
		assertTrue(diagnosticsVerified.tryAcquire(5, SECONDS));
	}

}
