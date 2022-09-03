package io.vena.bosk;

import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.util.Types;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.experimental.Accessors;

import static io.vena.bosk.util.ReflectionHelpers.setAccessible;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;

/**
 * Collection of utilities for implementing {@link Reference}s.
 *
 * @author pdoyle
 *
 */
public final class ReferenceUtils {

	@RequiredArgsConstructor
	@Value
	@Accessors(fluent = true)
	static class CatalogRef<E extends Entity> implements CatalogReference<E> {
		Reference<Catalog<E>> ref;
		Class<E> entryClass;

		@Override
		public CatalogReference<E> boundBy(BindingEnvironment bindings) {
			return new CatalogRef<>(ref.boundBy(bindings), entryClass());
		}

		@Override
		public Reference<E> then(Identifier id) {
			try {
				return ref.then(entryClass, id.toString());
			} catch (InvalidTypeException e) {
				throw new AssertionError("Entry class must match", e);
			}
		}

		@Override public Path path() { return ref.path(); }
		@Override public Class<Catalog<E>> targetClass() { return ref.targetClass(); }
		@Override public Type targetType() { return ref.targetType(); }
		@Override public Catalog<E> valueIfExists() { return ref.valueIfExists(); }
		@Override public void forEachValue(BiConsumer<Catalog<E>, BindingEnvironment> action, BindingEnvironment existingEnvironment) { ref.forEachValue(action, existingEnvironment); }
		@Override public <U> Reference<U> then(Class<U> targetClass, String... segments) throws InvalidTypeException { return ref.then(targetClass, segments); }
		@Override public <U extends Entity> CatalogReference<U> thenCatalog(Class<U> entryClass, String... segments) throws InvalidTypeException { return ref.thenCatalog(entryClass, segments); }
		@Override public <U extends Entity> ListingReference<U> thenListing(Class<U> entryClass, String... segments) throws InvalidTypeException { return ref.thenListing(entryClass, segments); }
		@Override public <U extends Entity, W> SideTableReference<U, W> thenSideTable(Class<U> keyClass, Class<W> valueClass, String... segments) throws InvalidTypeException { return ref.thenSideTable(keyClass, valueClass, segments); }
		@Override public <TT> Reference<Reference<TT>> thenReference(Class<TT> targetClass, String... segments) throws InvalidTypeException { return ref.thenReference(targetClass, segments); }
		@Override public <TT> Reference<TT> enclosingReference(Class<TT> targetClass) throws InvalidTypeException { return ref.enclosingReference(targetClass); }

		@Override public String toString() { return ref.toString(); }
	}

	@Value
	@Accessors(fluent = true)
	static class ListingRef<E extends Entity> implements ListingReference<E> {
		Reference<Listing<E>> ref;

		@Override
		public ListingReference<E> boundBy(BindingEnvironment bindings) {
			return new ListingRef<>(ref.boundBy(bindings));
		}

		@Override
		public Reference<ListingEntry> then(Identifier id) {
			try {
				return ref.then(ListingEntry.class, id.toString());
			} catch (InvalidTypeException e) {
				throw new AssertionError("Entry class must match", e);
			}
		}

		@Override public Path path() { return ref.path(); }
		@Override public Class<Listing<E>> targetClass() { return ref.targetClass(); }
		@Override public Type targetType() { return ref.targetType(); }
		@Override public Listing<E> valueIfExists() { return ref.valueIfExists(); }
		@Override public void forEachValue(BiConsumer<Listing<E>, BindingEnvironment> action, BindingEnvironment existingEnvironment) { ref.forEachValue(action, existingEnvironment); }
		@Override public <U> Reference<U> then(Class<U> targetClass, String... segments) throws InvalidTypeException { return ref.then(targetClass, segments); }
		@Override public <U extends Entity> CatalogReference<U> thenCatalog(Class<U> entryClass, String... segments) throws InvalidTypeException { return ref.thenCatalog(entryClass, segments); }
		@Override public <U extends Entity> ListingReference<U> thenListing(Class<U> entryClass, String... segments) throws InvalidTypeException { return ref.thenListing(entryClass, segments); }
		@Override public <U extends Entity, W> SideTableReference<U, W> thenSideTable(Class<U> keyClass, Class<W> valueClass, String... segments) throws InvalidTypeException { return ref.thenSideTable(keyClass, valueClass, segments); }
		@Override public <TT> Reference<Reference<TT>> thenReference(Class<TT> targetClass, String... segments) throws InvalidTypeException { return ref.thenReference(targetClass, segments); }
		@Override public <TT> Reference<TT> enclosingReference(Class<TT> targetClass) throws InvalidTypeException { return ref.enclosingReference(targetClass); }

		@Override public String toString() { return ref.toString(); }
	}

	@Accessors(fluent=true)
	@RequiredArgsConstructor
	static final class SideTableRef<K extends Entity,V> implements SideTableReference<K,V> {
		private final Reference<SideTable<K,V>> ref;
		private final @Getter Class<K> keyClass;
		private final @Getter Class<V> valueClass;

		@Override
		public Reference<V> then(Identifier id) {
			try {
				return ref.then(valueClass, id.toString());
			} catch (InvalidTypeException e) {
				throw new AssertionError("Value class must match", e);
			}
		}

		@Override public Reference<V> then(K key) { return this.then(key.id()); }

		@Override
		public SideTableReference<K, V> boundBy(BindingEnvironment bindings) {
			return new SideTableRef<>(ref.boundBy(bindings), keyClass(), valueClass());
		}

		@Override public Path path() { return ref.path(); }
		@Override public Class<SideTable<K,V>> targetClass() { return ref.targetClass(); }
		@Override public Type targetType() { return ref.targetType(); }
		@Override public SideTable<K,V> valueIfExists() { return ref.valueIfExists(); }
		@Override public void forEachValue(BiConsumer<SideTable<K,V>, BindingEnvironment> action, BindingEnvironment existingEnvironment) { ref.forEachValue(action, existingEnvironment); }
		@Override public <U> Reference<U> then(Class<U> targetClass, String... segments) throws InvalidTypeException { return ref.then(targetClass, segments); }
		@Override public <U extends Entity> CatalogReference<U> thenCatalog(Class<U> entryClass, String... segments) throws InvalidTypeException { return ref.thenCatalog(entryClass, segments); }
		@Override public <U extends Entity> ListingReference<U> thenListing(Class<U> entryClass, String... segments) throws InvalidTypeException { return ref.thenListing(entryClass, segments); }
		@Override public <U extends Entity,W> SideTableReference<U,W> thenSideTable(Class<U> keyClass, Class<W> valueClass, String... segments) throws InvalidTypeException { return ref.thenSideTable(keyClass, valueClass, segments); }
		@Override public <TT> Reference<Reference<TT>> thenReference(Class<TT> targetClass, String... segments) throws InvalidTypeException { return ref.thenReference(targetClass, segments); }
		@Override public <TT> Reference<TT> enclosingReference(Class<TT> targetClass) throws InvalidTypeException { return ref.enclosingReference(targetClass); }

		@Override public boolean equals(Object obj) {
			if (obj == this) {
				return true;
			} else if (obj instanceof Reference) {
				return obj.equals(ref);
			} else {
				return false;
			}
		}

		@Override public int hashCode() { return ref.hashCode(); }
		@Override public String toString() { return ref.toString(); }
	}

	/**
	 * Lookup a type parameter of a given generic class made concrete by a given parameterized subtype.
	 *
	 * <p>
	 * This stuff can get incredibly abstract. Let's consider these specific types as an example:
	 *
	 * <pre>
interface S&lt;A,B> {}
interface I&lt;C,D> implements S&lt;A,B> {}
class C&lt;T> implements I<T,Integer> {}
...
C&lt;String> someField;
	 * </pre>
	 *
	 * Note that the type <code>C&lt;String></code> implements (indirectly)
	 * <code>S&lt;String,Integer></code>. That means in the context of <code>C&lt;String></code>,
	 * type parameter 0 of <code>S</code> would be <code>String</code>.
	 *
	 * <p>
	 * Hence, if you use reflection to get a {@link Field} <code>f</code> for
	 * <code>someField</code>, then calling
	 * <code>parameterType(f.getGenericType(), S.class, 0)</code> would return
	 * <code>String.class</code>.
	 *
	 * @param parameterizedType The {@link Type} providing the context for the parameter lookup
	 * @param genericClass The generic class whose parameter you want
	 * @param index The position of the desired parameter within the parameter list of <code>genericClass</code>
	 * @return the {@link Type} of the desired parameter
	 */
	public static Type parameterType(Type parameterizedType, Class<?> genericClass, int index) {
		Class<?> actualClass = rawClass(parameterizedType);
		assert genericClass.isAssignableFrom(actualClass): genericClass.getSimpleName() + " must be assignable from " + parameterizedType;
		if (actualClass == genericClass) {
			return parameterType(parameterizedType, index);
		} else try {
			// We're dealing with inheritance. Find which of our
			// superclass/superinterfaces to pursue.
			//
			// Repeated inheritance of the same interface is not a problem
			// because Java's generics require that multiply-implemented
			// interfaces must have consistent types, so any occurrence of the
			// interface will serve.
			//
			Type supertype = actualClass.getGenericSuperclass();
			if (supertype == null || !genericClass.isAssignableFrom(rawClass(supertype))) {
				// Must come from interface inheritance
				supertype = null; // Help catch errors
				for (Type candidate: actualClass.getGenericInterfaces()) {
					if (genericClass.isAssignableFrom(rawClass(candidate))) {
						supertype = candidate;
						break;
					}
				}
				assert supertype != null: "If genericClass isAssignableFrom actualClass, and they're not equal, then it must be assignable from something actualClass inherits";
			}

			// Recurse with supertype
			Type returned = parameterType(supertype, genericClass, index);

			if (returned instanceof TypeVariable) {
				// The recursive call has returned us one of the type variables
				// from our own generic class.  For example, if parameterizedType
				// were C<String> and C was declared as C<T> extends S<U>, then
				// `returned` is T, and it's our job here to resolve it back to String.
				TypeVariable<?>[] typeVariables = actualClass.getTypeParameters();
				for (int i = 0; i < typeVariables.length; i++) {
					if (returned.equals(typeVariables[i])) {
						return parameterType(parameterizedType, i);
					}
				}
				throw new AssertionError("Expected type variable match for " + returned + " in " + actualClass.getSimpleName() + " type parameters: " + Arrays.toString(actualClass.getTypeParameters()));
			} else {
				return returned;
			}
		} catch (AssertionError e) {
			// Help diagnose assertion errors from recursive calls
			throw new AssertionError(format("parameterType(%s, %s, %s): %s", parameterizedType, genericClass, index, e.getMessage()), e);
		}
	}

	static Type parameterType(Type parameterizedType, int index) {
		return ((ParameterizedType)parameterizedType).getActualTypeArguments()[index];
	}

	/**
	 * Retains type information in the form of {@link Type} objects, rather
	 * than the {@link Class} objects available via {@link MethodType},
	 * because we need information about generic type parameters.
	 *
	 * @author pdoyle
	 */
	@Value
	@Accessors(fluent = true)
	static class TypedHandle {
		// The order of the fields here is supposed to remind you of a method declaration: ReturnType methodName(ArgType x, ArgType y)
		Type returnType;
		MethodHandle handle;
		List<Type> argTypes;

		public TypedHandle(Type returnType, MethodHandle handle, List<Type> argTypes) {
			this.returnType = returnType;
			this.handle = handle;
			this.argTypes = argTypes;
			if (!rawClass(returnType).isAssignableFrom(handle.type().returnType())) {
				throw new IllegalArgumentException("Given return type doesn't match return type of " + handle + ": " + returnType);
			}
			for (int i = 0; i < argTypes.size(); i++) {
				if (!rawClass(argTypes.get(i)).isAssignableFrom(handle.type().parameterType(i))) {
					throw new IllegalArgumentException("Given type of parameter " + i + " doesn't match.\nHandle: " + handle.type().parameterList() + "\nArgTypes: " + argTypes);
				}
			}
			if (handle.type().parameterCount() > argTypes.size()) {
				throw new IllegalArgumentException("Given type has only " + argTypes.size() + " arguments; doesn't match handle " + handle);
			}
		}

		public TypedHandle(Type returnType, MethodHandle handle, Type...argTypes) {
			this(returnType, handle, asList(argTypes));
		}
	}

	public static Class<?> rawClass(Type sourceType) {
		if (sourceType instanceof ParameterizedType) {
			return (Class<?>)((ParameterizedType) sourceType).getRawType();
		} else {
			return (Class<?>)sourceType;
		}
	}

	public static Type referenceTypeFor(Type targetType) {
		return Types.parameterizedType(Reference.class, targetType);
	}

	public static Method getterMethod(Class<?> objectClass, String fieldName) throws InvalidTypeException {
		String methodName = fieldName; // fluent
		for (Class<?> c = objectClass; c != Object.class; c = c.getSuperclass()) {
			try {
				Method result = c.getDeclaredMethod(methodName);
				if (result.getParameterCount() != 0) {
					throw new InvalidTypeException("Getter method \"" + methodName + "\" has unexpected arguments: " + Arrays.toString(result.getParameterTypes()));
				}
				return setAccessible(result);
			} catch (NoSuchMethodException e) {
				// No prob; try the superclass
			}
		}
		throw new InvalidTypeException("No method \"" + methodName + "()\" in type " + objectClass.getSimpleName());
	}

	public static <T> Constructor<T> theOnlyConstructorFor(Class<T> nodeClass) {
		List<Constructor<?>> constructors = Stream.of(nodeClass.getDeclaredConstructors())
				.filter(ctor -> !ctor.isSynthetic())
				.collect(toList());
		if (constructors.size() != 1) {
			throw new IllegalArgumentException("Ambiguous constructor list: " + constructors);
		}
		@SuppressWarnings("unchecked")
		Constructor<T> theConstructor = (Constructor<T>) constructors.get(0);
		return setAccessible(theConstructor);
	}

	public static Map<String, Method> gettersForConstructorParameters(Class<?> nodeClass) throws InvalidTypeException {
		Iterable<String> names = Stream
			.of(theOnlyConstructorFor(nodeClass).getParameters())
			.map(Parameter::getName)
			::iterator;
		Map<String, Method> result = new LinkedHashMap<>();
		for (String name: names) {
			result.put(name, getterMethod(nodeClass, name));
		}
		return result;
	}

}
