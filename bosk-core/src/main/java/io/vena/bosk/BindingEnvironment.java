package io.vena.bosk;

import io.vena.bosk.exceptions.ParameterAlreadyBoundException;
import io.vena.bosk.exceptions.ParameterUnboundException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

import static io.vena.bosk.Path.isValidParameterName;
import static io.vena.bosk.Path.parameterNameFromSegment;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static java.util.Collections.unmodifiableMap;
import static lombok.AccessLevel.PRIVATE;

/**
 * A mapping from {@link String} names to {@link Identifier} values.
 * Used to supply or extract values for parameters in a parameterized {@link Path}
 * or {@link Reference}.
 */
@RequiredArgsConstructor(access = PRIVATE)
@EqualsAndHashCode
public final class BindingEnvironment {
	/**
	 * This should use an ordered map, like LinkedHashMap, because in some contexts,
	 * parameters are processed positionally, so the order can be significant, and
	 * we don't want any surprises.
	 */
	private final Map<String, Identifier> bindings;

	/**
	 * @return an environment with no names bound
	 */
	public static BindingEnvironment empty() {
		return EMPTY_ENVIRONMENT;
	}

	/**
	 * @return an environment with the given <code>name</code> bound to the given <code>value</code>,
	 *         and no other names bound
	 * @throws IllegalArgumentException if the given name is not a valid parameter name
	 */
	public static BindingEnvironment singleton(String name, Identifier value) {
		if (!isValidParameterName(name)) {
			throw new IllegalArgumentException("Invalid parameter name \"" + name + "\"");
		}
		return new BindingEnvironment(singletonMap(name, value));
	}

	/**
	 * @return a {@link Builder} initialized with all the bindings from this environment
	 */
	public Builder builder() {
		return new Builder(bindings);
	}

	/**
	 * @return a new environment containing all the bindings from this environment,
	 * overridden with all bindings from <code>other</code> as though by {@link Builder#rebind}.
	 */
	public BindingEnvironment overlay(BindingEnvironment other) {
		Builder builder = this.builder();
		other.forEach(builder::rebind);
		return builder.build();
	}

	/**
	 * @param name (without the surrounding hyphens)
	 * @throws ParameterUnboundException if the name is not bound
	 * @throws IllegalArgumentException if the name is not valid
	 * @return The value bound to the given <code>name</code>
	 */
	public Identifier get(String name) {
		Identifier result = bindings.get(validParameterName(name));
		if (result == null) {
			throw new ParameterUnboundException("No binding for \"" + name + "\"");
		} else {
			return result;
		}
	}

	Identifier getForParameterSegment(String segment) {
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

	/**
	 * @return the value bound to <code>name</code>,
	 * or <code>defaultValue</code> if <code>name</code> is not bound
	 */
	public Identifier getOrDefault(String name, Identifier defaultValue) {
		return bindings.getOrDefault(name, defaultValue);
	}

	/**
	 * Performs the given <code>action</code> for each binding in this environment
	 * until all bindings have been processed or <code>action</code> throws an exception.
	 */
	public void forEach(BiConsumer<String, Identifier> action) {
		bindings.forEach(action);
	}

	/**
	 * @return an unmodifiable {@link Map} containing all the bindings in this environment
	 */
	public Map<String, Identifier> asMap() {
		return unmodifiableMap(bindings);
	}

	public static final class Builder {
		private final Map<String, Identifier> bindings = new LinkedHashMap<>();

		public static Builder empty() {
			return new Builder();
		}

		Builder() { }

		Builder(Map<String, Identifier> existingBindings) {
			bindings.putAll(existingBindings);
		}

		/**
		 * Binds <code>name</code> to <code>value</code>.
		 * @return <code>this</code>
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
		 * @return <code>this</code>
		 */
		public Builder rebind(String name, Identifier value) {
			bindings.put(name, value);
			return this;
		}

		/**
		 * Causes the given name to be unbound in this environment.
		 * @return <code>this</code>
		 */
		public Builder unbind(String name) {
			bindings.remove(name);
			return this;
		}

		/**
		 * Can be called more than once.
		 * @return a {@link BindingEnvironment} with the desired contents
		 */
		public BindingEnvironment build() {
			return new BindingEnvironment(unmodifiableMap(new LinkedHashMap<>(bindings)));
		}

	}

	@Override
	public String toString() {
		return bindings.toString();
	}

	private static final BindingEnvironment EMPTY_ENVIRONMENT = new BindingEnvironment(emptyMap());
}
