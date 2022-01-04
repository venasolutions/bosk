package org.vena.bosk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.vena.bosk.exceptions.ParameterAlreadyBoundException;
import org.vena.bosk.exceptions.ParameterUnboundException;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static java.util.Collections.unmodifiableMap;
import static lombok.AccessLevel.PRIVATE;
import static org.vena.bosk.Path.isValidParameterName;
import static org.vena.bosk.Path.parameterNameFromSegment;

@RequiredArgsConstructor(access = PRIVATE)
@EqualsAndHashCode
public final class BindingEnvironment {
	/**
	 * This should use an ordered map, like LinkedHashMap, because in some contexts,
	 * parameters are processed positionally, so the order can be significant, and
	 * we don't want any surprises.
	 */
	private final Map<String, Identifier> bindings;

	public static BindingEnvironment empty() {
		return EMPTY_ENVIRONMENT;
	}

	public static BindingEnvironment singleton(String name, Identifier value) {
		if (!isValidParameterName(name)) {
			throw new IllegalArgumentException("Invalid parameter name \"" + name + "\"");
		}
		return new BindingEnvironment(singletonMap(name, value));
	}

	public Builder builder() {
		return new Builder(bindings);
	}

	public BindingEnvironment overlay(BindingEnvironment other) {
		Builder builder = this.builder();
		other.forEach(builder::rebind);
		return builder.build();
	}

	/**
	 * @param name (without the surrounding hyphens)
	 * @throws ParameterUnboundException if the name is not bound
	 * @throws IllegalArgumentException if the name is not valid
	 */
	public Identifier get(String name) {
		Identifier result = bindings.get(validParameterName(name));
		if (result == null) {
			throw new ParameterUnboundException("No binding for \"" + name + "\"");
		} else {
			return result;
		}
	}

	public Identifier getForParameterSegment(String segment) {
		return get(parameterNameFromSegment(segment));
	}

	private static String validParameterName(String name) {
		if (isValidParameterName(name)) {
			return name;
		} else if (name.startsWith("-")) {
			// Give a slightly more helpful error for the case where the user has passed in the whole
			// path segments, hyphens and all.
			throw new IllegalArgumentException("Parameter name cannot start with a hyphen: \"" + name + "\"");
		} else {
			throw new IllegalArgumentException("Invalid parameter name: \"" + name + "\"");
		}
	}

	public Identifier getOrDefault(String name, Identifier defaultValue) {
		return bindings.getOrDefault(name, defaultValue);
	}

	public void forEach(BiConsumer<String, Identifier> action) {
		bindings.forEach(action);
	}

	public Map<String, Identifier> asMap() {
		return bindings;
	}

	public static final class Builder {
		private final Map<String, Identifier> bindings = new LinkedHashMap<>();

		public static Builder empty() {
			return new Builder();
		}

		Builder() { }

		Builder(Map<String, Identifier> existingBindings) {
			existingBindings.forEach(bindings::put);
		}

		/**
		 * Binds <code>name</code> to <code>value</code>.
		 * @throws ParameterAlreadyBoundException if <code>name</code> has a binding.
		 */
		public Builder bind(String name, Identifier value) {
			Identifier old = bindings.put(validParameterName(name), value);
			if (old == null) {
				return this;
			} else {
				throw new ParameterAlreadyBoundException("Cannot bind parameter \"" + name + "\" to \"" + value + "\": already bound to \"" + old + "\"");
			}
		}

		/**
		 * Binds <code>name</code> to <code>value</code> regardless of whether <code>name</code> is already bound.
		 */
		public Builder rebind(String name, Identifier value) {
			bindings.put(name, value);
			return this;
		}

		public Builder unbind(String name) {
			bindings.remove(name);
			return this;
		}

		/**
		 * @return a {@link BindingEnvironment}. Can be called more than once.
		 * May or may not return the same object (since it's immutable anyway).
		 */
		public BindingEnvironment build() {
			Map<String, Identifier> map = new LinkedHashMap<>();
			bindings.forEach(map::put);
			return new BindingEnvironment(unmodifiableMap(map));
		}

	}

	@Override
	public String toString() {
		return bindings.toString();
	}

	private static final BindingEnvironment EMPTY_ENVIRONMENT = new BindingEnvironment(emptyMap());
}
