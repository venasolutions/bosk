package io.vena.bosk.drivers.mongo;

import org.slf4j.MDC;

final class MappedDiagnosticContext {
	static MDCScope setupMDC(String boskName) {
		MDCScope result = new MDCScope();
		MDC.put(MDC_KEY, " [" + boskName + "]");
		return result;
	}

	/**
	 * This is like {@link org.slf4j.MDC.MDCCloseable} except instead of
	 * deleting the MDC entry at the end, it restores it to its prior value,
	 * which allows us to nest these.
	 *
	 * <p>
	 * You are going to have the urge to use this in an existing
	 * try block that has catch and finally clauses. Resist that urge.
	 * The catch and finally blocks will run after {@link #close()},
	 * and so they won't benefit from the diagnostic context.
	 * You really want to use this in a try block that has no catch or finally clause.
	 */
	static final class MDCScope implements AutoCloseable {
		final String oldValue = MDC.get(MDC_KEY);
		@Override public void close() { MDC.put(MDC_KEY, oldValue); }
	}

	private static final String MDC_KEY = "bosk.MongoDriver";
}
