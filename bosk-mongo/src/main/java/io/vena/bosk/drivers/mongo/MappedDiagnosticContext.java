package io.vena.bosk.drivers.mongo;

import io.vena.bosk.Identifier;
import org.slf4j.MDC;

import static io.vena.bosk.drivers.mongo.MdcKeys.BOSK_INSTANCE_ID;
import static io.vena.bosk.drivers.mongo.MdcKeys.BOSK_NAME;

final class MappedDiagnosticContext {

	static MDCScope setupMDC(String boskName, Identifier boskID) {
		MDCScope result = new MDCScope();
		MDC.put(BOSK_NAME, boskName);
		MDC.put(BOSK_INSTANCE_ID, boskID.toString());
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
		final String oldName = MDC.get(BOSK_NAME);
		final String oldID = MDC.get(BOSK_INSTANCE_ID);
		@Override public void close() {
			MDC.put(BOSK_NAME, oldName);
			MDC.put(BOSK_INSTANCE_ID, oldID);
		}
	}

}
