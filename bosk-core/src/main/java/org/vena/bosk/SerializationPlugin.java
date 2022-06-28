package org.vena.bosk;

import com.google.gson.JsonParseException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.experimental.Accessors;
import org.vena.bosk.annotations.DeserializationPath;
import org.vena.bosk.annotations.Enclosing;
import org.vena.bosk.annotations.Self;
import org.vena.bosk.exceptions.InvalidTypeException;
import org.vena.bosk.exceptions.MalformedPathException;
import org.vena.bosk.exceptions.ParameterUnboundException;
import org.vena.bosk.exceptions.UnexpectedPathException;

import static java.util.Objects.requireNonNull;
import static org.vena.bosk.ReferenceUtils.parameterType;
import static org.vena.bosk.ReferenceUtils.rawClass;
import static org.vena.bosk.ReferenceUtils.theOnlyConstructorFor;

/**
 * A "Plugin", for now, is a thing that translates Bosk objects for interfacing
 * with the outside world.  One fine day, we'll think of a better name.
 *
 * <p>
 * Serialization systems are generally not good at allowing custom logic to
 * supply any context. This class works around that limitation by supplying a
 * place to put some context, maintained using {@link ThreadLocal}s, and managed
 * using the {@link DeserializationScope} auto-closeable to make sure the thread-local context
 * state is managed correctly.
 *
 * <p>
 * Generally, applications create one instance of each plugin they need.
 * Instances are thread-safe. The only case where you might want another
 * instance is if you need to perform a second, unrelated, nested
 * deserialization while one is already in progress on the same thread. It's
 * hard to think of a reason that an application would want to do this.
 *
 * @author pdoyle
 *
 */
public abstract class SerializationPlugin {
	private final ThreadLocal<DeserializationScope> currentScope = ThreadLocal.withInitial(this::outermostScope);

	public final DeserializationScope newDeserializationScope(Path newPath) {
		DeserializationScope outerScope = currentScope.get();
		DeserializationScope newScope = new NestedDeserializationScope(outerScope, newPath, outerScope.bindingEnvironment());
		currentScope.set(newScope);
		return newScope;
	}

	public final DeserializationScope newDeserializationScope(Reference<?> ref) {
		return newDeserializationScope(ref.path());
	}

	public final DeserializationScope overlayScope(BindingEnvironment env) {
		DeserializationScope outerScope = currentScope.get();
		DeserializationScope newScope = new NestedDeserializationScope(outerScope, outerScope.path(), outerScope.bindingEnvironment().overlay(env));
		currentScope.set(newScope);
		return newScope;
	}

	public final DeserializationScope innerDeserializationScope(String lastSegment) {
		DeserializationScope outerScope = currentScope.get();
		DeserializationScope newScope = new NestedDeserializationScope(outerScope, outerScope.path().then(lastSegment), outerScope.bindingEnvironment());
		currentScope.set(newScope);
		return newScope;
	}

	public final DeserializationScope nodeFieldDeserializationScope(Class<?> nodeClass, String fieldName) {
		DeserializationPath annotation = infoFor(nodeClass).annotatedParameters_DeserializationPath.get(fieldName);
		if (annotation == null) {
			return innerDeserializationScope(fieldName);
		} else {
			DeserializationScope outerScope = currentScope.get();
			try {
				Path path = Path
					.parseParameterized(annotation.value())
					.boundBy(outerScope.bindingEnvironment());
				if (path.numParameters() == 0) {
					DeserializationScope newScope = new NestedDeserializationScope(outerScope, path, outerScope.bindingEnvironment());
					currentScope.set(newScope);
					return newScope;
				} else {
					throw new ParameterUnboundException(
						"Unbound parameters in @"
							+ DeserializationPath.class.getSimpleName() + "(\"" + path + "\") "
							+ nodeClass.getSimpleName() + "." + fieldName + " ");
				}
			} catch (MalformedPathException e) {
				throw new MalformedPathException("Invalid DeserializationPath for "
					+ nodeClass.getSimpleName()
					+ "." + fieldName
					+ ": " + e.getMessage(), e);
			}
		}
	}

	private DeserializationScope outermostScope() {
		return new OutermostDeserializationScope();
	}

	public static abstract class DeserializationScope implements AutoCloseable {
		DeserializationScope(){}

		public abstract Path path();
		public abstract BindingEnvironment bindingEnvironment();

		@Override public abstract void close();
	}

	private static final class OutermostDeserializationScope extends DeserializationScope {
		@Override public Path path() { return Path.empty(); }
		@Override public BindingEnvironment bindingEnvironment() { return BindingEnvironment.empty(); }

		@Override
		public void close() {
			throw new IllegalStateException("Outermost scope should never be closed");
		}
	}

	@Value
	@EqualsAndHashCode(callSuper = false)
	@Accessors(fluent = true)
	private class NestedDeserializationScope extends DeserializationScope {
		DeserializationScope outer;
		Path path;
		BindingEnvironment bindingEnvironment;

		@Override
		public void close() {
			currentScope.set(requireNonNull(outer));
		}
	}

	/**
	 * Turns <code>parameterValuesByName</code> into a list suitable for
	 * passing to a constructor, in the order indicated by
	 * <code>parametersByName</code>.
	 *
	 *
	 * @param parameterValuesByName values read from the input. <em>Modified by this method.<em>
	 * @param parametersByName ordered map of constructor {@link Parameter}s.
	 * @return {@link List} of parameter values to pass to the constructor, in
	 * the same order as in <code>parametersByName</code>. Missing values are
	 * supplied where possible, such as <code>Optional.empty()</code> and
	 * {@link Enclosing} references.
	 */
	public final List<Object> parameterValueList(Class<?> nodeClass, Map<String, Object> parameterValuesByName, LinkedHashMap<String, Parameter> parametersByName, Bosk<?> bosk) {
		List<Object> parameterValues = new ArrayList<>();
		for (Entry<String, Parameter> entry: parametersByName.entrySet()) {
			String name = entry.getKey();
			Parameter parameter = entry.getValue();
			Class<?> type = parameter.getType();
			Reference<?> implicitReference = findImplicitReferenceIfAny(nodeClass, parameter, bosk);

			Object value = parameterValuesByName.remove(name);
			if (value == null) {
				// Field is absent in the input
				if (implicitReference != null) {
					parameterValues.add(implicitReference);
				} else if (Optional.class.equals(type)) {
					parameterValues.add(Optional.empty());
				} else if (Phantom.class.equals(type)) {
					parameterValues.add(Phantom.empty());
				} else {
					throw new JsonParseException("Missing field: " + name);
				}
			} else if (implicitReference == null) {
				parameterValues.add(value);
			} else {
				throw new JsonParseException("Unexpected field \"" + name + "\" for implicit reference");
			}
		}
		if (parameterValuesByName.size() >= 1) {
			throw new JsonParseException("Unrecognized fields: " + parameterValuesByName.keySet());
		}
		return parameterValues;
	}

	public static boolean isSelfReference(Class<?> nodeClass, Parameter parameter) {
		return infoFor(nodeClass).getAnnotatedParameters_Self().contains(parameter.getName());
	}

	public static boolean isEnclosingReference(Class<?> nodeClass, Parameter parameter) {
		return infoFor(nodeClass).getAnnotatedParameters_Enclosing().contains(parameter.getName());
	}

	public static boolean hasDeserializationPath(Class<?> nodeClass, Parameter parameter) {
		return infoFor(nodeClass).getAnnotatedParameters_DeserializationPath().containsKey(parameter.getName());
	}

	private Reference<?> findImplicitReferenceIfAny(Class<?> nodeClass, Parameter parameter, Bosk<?> bosk) {
		if (isSelfReference(nodeClass, parameter)) {
			Class<?> targetClass = rawClass(parameterType(parameter.getParameterizedType(), Reference.class, 0));
			return selfReference(targetClass, bosk);
		} else if (isEnclosingReference(nodeClass, parameter)) {
			Class<?> targetClass = rawClass(parameterType(parameter.getParameterizedType(), Reference.class, 0));
			Reference<Object> selfRef = selfReference(Object.class, bosk);
			try {
				return selfRef.enclosingReference(targetClass);
			} catch (InvalidTypeException e) {
				// TODO: Validation needs to check that every location
				// where this type appears in the document tree is
				// contained in a document of the target class.
				throw new UnexpectedPathException("Enclosing reference validation: Error looking up Enclosing ref \"" + parameter.getName() + "\": " + e.getMessage(), e);
			}
		} else {
			return null;
		}
	}

	private <T> Reference<T> selfReference(Class<T> targetClass, Bosk<?> bosk) throws AssertionError {
		Path currentPath = currentScope.get().path();
		try {
			return bosk.reference(targetClass, currentPath);
		} catch (InvalidTypeException e) {
			throw new UnexpectedPathException("currentDeserializationPath should be valid: \"" + currentPath + "\"", e);
		}
	}

	/**
	 * @return true if the given parameter is computed automatically during
	 * deserialization, and therefore does not appear in the serialized output.
	 */
	public static boolean isImplicitParameter(Class<?> nodeClass, Parameter parameter) {
		String name = parameter.getName();
		ParameterInfo info = infoFor(nodeClass);
		return info.annotatedParameters_Self.contains(name)
			|| info.annotatedParameters_Enclosing.contains(name);
	}


	private static ParameterInfo infoFor(Class<?> nodeClassArg) {
		return PARAMETER_INFO_MAP.computeIfAbsent(nodeClassArg, SerializationPlugin::computeInfoFor);
	}

	private static ParameterInfo computeInfoFor(Class<?> nodeClassArg) {
		Set<String> selfParameters = new HashSet<>();
		Set<String> enclosingParameters = new HashSet<>();
		Map<String, DeserializationPath> deserializationPathParameters = new HashMap<>();
		for (Parameter parameter: theOnlyConstructorFor(nodeClassArg).getParameters()) {
			scanForInfo(parameter, parameter.getName(),
				selfParameters, enclosingParameters, deserializationPathParameters);
		}

		// Bosk generally ignores an object's fields, looking only at its
		// constructor arguments and its getters. However, we make an exception
		// for convenience: Bosk annotations that go on constructor parameters
		// can also go on fields with the same name. This accommodates systems
		// like Lombok or Java 14's Records that derive constructors from fields.

		for (Class<?> c = nodeClassArg; c != Object.class; c = c.getSuperclass()) {
			for (Field field: nodeClassArg.getDeclaredFields()) {
				scanForInfo(field, field.getName(),
					selfParameters, enclosingParameters, deserializationPathParameters);
			}
		}
		return new ParameterInfo(selfParameters, enclosingParameters, deserializationPathParameters);
	}

	private static void scanForInfo(AnnotatedElement thing, String name, Set<String> selfParameters, Set<String> enclosingParameters, Map<String, DeserializationPath> deserializationPathParameters) {
		if (thing.isAnnotationPresent(Self.class)) {
			selfParameters.add(name);
		} else if (thing.isAnnotationPresent(Enclosing.class)) {
			enclosingParameters.add(name);
		} else if (thing.isAnnotationPresent(DeserializationPath.class)) {
			deserializationPathParameters.put(name, thing.getAnnotation(DeserializationPath.class));
		}
	}

	@Value
	private static class ParameterInfo {
		Set<String> annotatedParameters_Self;
		Set<String> annotatedParameters_Enclosing;
		Map<String, DeserializationPath> annotatedParameters_DeserializationPath;
	}

	private static final Map<Class<?>, ParameterInfo> PARAMETER_INFO_MAP = new ConcurrentHashMap<>();

}
