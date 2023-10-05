package io.vena.bosk;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A thread-local set of name-value pairs that propagate all the way from
 * submission of a driver update, through all the driver layers,
 * to the execution of hooks.
 */
public final class BoskDiagnosticContext {
	private final ThreadLocal<MapValue<String>> currentAttributes = ThreadLocal.withInitial(MapValue::empty);

	public final class DiagnosticScope implements AutoCloseable {
		final MapValue<String> oldAttributes = currentAttributes.get();

		DiagnosticScope(MapValue<String> attributes) {
			currentAttributes.set(attributes);
		}

		@Override
		public void close() {
			currentAttributes.set(oldAttributes);
		}
	}

	/**
	 * @return the current thread's value of the attribute with the given <code>name</code>,
	 * or <code>null</code> if no such attribute has been defined.
	 */
	public @Nullable String getAttribute(String name) {
		return currentAttributes.get().get(name);
	}

	public @NotNull MapValue<String> getAttributes() {
		return currentAttributes.get();
	}

	/**
	 * Adds a single attribute to the current thread's diagnostic context.
	 * If the attribute already exists, it will be replaced.
	 */
	public DiagnosticScope withAttribute(String name, String value) {
		return new DiagnosticScope(currentAttributes.get().with(name, value));
	}

	/**
	 * Adds attributes to the current thread's diagnostic context.
	 * If an attribute already exists, it will be replaced.
	 */
	public DiagnosticScope withAttributes(@NotNull MapValue<String> additionalAttributes) {
		return new DiagnosticScope(currentAttributes.get().withAll(additionalAttributes));
	}

	/**
	 * Replaces all attributes in the current thread's diagnostic context.
	 * Existing attributes are removed/replaced.
	 * <p>
	 * This is intended for propagating context from one thread to another.
	 * <p>
	 * If <code>attributes</code> is null, this is a no-op, and any existing attributes on this thread are retained.
	 * If ensuring a clean set of attributes is important, pass an empty map instead of null.
	 */
	public DiagnosticScope withOnly(@Nullable MapValue<String> attributes) {
		if (attributes == null) {
			return new DiagnosticScope(currentAttributes.get());
		} else {
			return new DiagnosticScope(attributes);
		}
	}
}
