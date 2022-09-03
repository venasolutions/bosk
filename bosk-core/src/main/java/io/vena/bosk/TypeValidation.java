package io.vena.bosk;

import io.vena.bosk.annotations.DerivedRecord;
import io.vena.bosk.annotations.DeserializationPath;
import io.vena.bosk.annotations.Enclosing;
import io.vena.bosk.annotations.Self;
import io.vena.bosk.exceptions.InvalidFieldTypeException;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.vena.bosk.ReferenceUtils.getterMethod;
import static io.vena.bosk.ReferenceUtils.parameterType;
import static io.vena.bosk.ReferenceUtils.rawClass;
import static io.vena.bosk.ReferenceUtils.theOnlyConstructorFor;
import static io.vena.bosk.SerializationPlugin.hasDeserializationPath;
import static io.vena.bosk.SerializationPlugin.isEnclosingReference;
import static io.vena.bosk.SerializationPlugin.isSelfReference;
import static java.lang.reflect.Modifier.isFinal;
import static java.lang.reflect.Modifier.isPublic;
import static java.lang.reflect.Modifier.isStatic;
import static java.util.Arrays.asList;
import static java.util.Collections.newSetFromMap;

/**
 * Checks that a given type conforms to the rules for a {@link Bosk} root type.
 */
public final class TypeValidation {

	public static void validateType(Type rootType) throws InvalidTypeException {
		Class<?> rootClass = rawClass(rootType);
		if (!Entity.class.isAssignableFrom(rootClass)) { // pdoyle - I see this becoming StateTreeNode in our near future
			throw new InvalidTypeException("Bosk root type must be an Entity; " + rootClass.getSimpleName() + " is not an Entity");
		}
		validateType(rootType, newSetFromMap(new IdentityHashMap<>()));
	}

	private static void validateType(Type theType, Set<Type> alreadyValidated) throws InvalidTypeException {
		if (alreadyValidated.add(theType)) {
			Class<?> theClass = rawClass(theType);
			if (!isPublic(theClass.getModifiers())) {
				throw new InvalidTypeException("Class is not public: " + theClass.getName());
			} else if (isSimpleClass(theClass)) {
				// All allowed
				return;
			} else if (theClass.isAnnotationPresent(DerivedRecord.class)) {
				throw new InvalidTypeException(DerivedRecord.class.getSimpleName() + " types are not allowed in a Bosk");
			} else if (Reference.class.isAssignableFrom(theClass)) {
				validateFieldsAreFinal(theClass);
				Type targetType = parameterType(theType, Reference.class, 0);
				Class<?> targetClass = rawClass(targetType);
				if (Reference.class.isAssignableFrom(targetClass)) {
					throw new InvalidTypeException("Reference to Reference is not allowed: " + theType);
				}

				// It's ok to have a reference to a supertype of a valid type,
				// like Reference<Entity>. As nice as it might be to fail early
				// for a bosk created using an impossible Reference type, nothing
				// bad can really happen if we allow it, because such references
				// couldn't be constructed anyway.
				//
				// But if the type is final, and it's not valid, there's no
				// chance to make it right.
				//
				if (Modifier.isFinal(targetClass.getModifiers())) {
					validateType(targetType, alreadyValidated);
				}
			} else if (StateTreeNode.class.isAssignableFrom(theClass)) {
				validateStateTreeNodeClass(theClass, alreadyValidated);
			} else if (ListValue.class.isAssignableFrom(theClass) || MapValue.class.isAssignableFrom(theClass)) {
				validateFieldsAreFinal(theClass);
				Class<?> genericClass = ListValue.class.isAssignableFrom(theClass) ? ListValue.class : MapValue.class;
				Type entryType = parameterType(theType, genericClass, 0);
				Class<?> entryClass = rawClass(entryType);
				// Exclude specific anti-patterns
				if (Optional.class.isAssignableFrom(entryClass)) {
					throw new InvalidTypeException("Optional is not allowed in a " + ListValue.class.getSimpleName());
				} else if (Phantom.class.isAssignableFrom(entryClass)) {
					throw new InvalidTypeException("Phantom is not allowed in a " + ListValue.class.getSimpleName());
				} else if (Entity.class.isAssignableFrom(entryClass)) {
					throw new InvalidTypeException(entryClass.getSimpleName() + " Entity is not allowed in a " + ListValue.class.getSimpleName() + "; use Catalog");
				} else if (Identifier.class.isAssignableFrom(entryClass) || Reference.class.isAssignableFrom(entryClass)) {
					throw new InvalidTypeException(entryClass.getSimpleName() + " is not allowed in a " + ListValue.class.getSimpleName() + "; use Listing");
				}
				// Otherwise, any valid entryType is ok
				validateType(entryType, alreadyValidated);
				if (ListValue.class.isAssignableFrom(theClass)) try {
					// For ListValue, deserialization currently supports only one constructor that accepts an array
					Constructor<?> ctor = theOnlyConstructorFor(theClass);
					Class<?>[] parameterTypes = ctor.getParameterTypes();
					if (parameterTypes.length != 1 || !parameterTypes[0].isArray()) {
						// We could do a little better here. The array element type should
						// be compatible with elementType.
						throw new InvalidTypeException(theClass.getSimpleName() + " must have one constructor taking an array");
					}
				} catch (IllegalArgumentException e) {
					throw new InvalidTypeException(theClass.getSimpleName() + ": " + e.getMessage(), e);
				}
			} else {
				for (Class<?> validClass: TRUSTED_IMMUTABLE_CLASSES) {
					if (validClass.equals(theClass)) {
						if (theType instanceof ParameterizedType) {
							int numTypeParameters = validClass.getTypeParameters().length;
							for (int i = 0; i < numTypeParameters; i++) {
								Type typeParameter = parameterType(theType, validClass, i);
								validateType(typeParameter, alreadyValidated);
							}
						}
						// All good
						return;
					}
				}
				throw new InvalidTypeException(theClass.getSimpleName() + " is not allowed in a bosk");
			}
		}
	}

	private static boolean isSimpleClass(Class<?> theClass) {
		if (theClass.isPrimitive() || theClass.isEnum()) {
			return true;
		} else {
			for (Class<?> simpleClass: SIMPLE_VALUE_CLASSES) {
				if (simpleClass.isAssignableFrom(theClass)) {
					return true;
				}
			}
		}
		return false;
	}

	private static void validateStateTreeNodeClass(Class<?> nodeClass, Set<Type> alreadyValidated) throws InvalidTypeException {
		Constructor<?>[] constructors = nodeClass.getConstructors();
		if (constructors.length != 1) {
			throw new InvalidTypeException(nodeClass.getSimpleName() + " must have one constructor; found " + constructors.length + " constructors");
		}

		// Every constructor parameter must have an appropriate getter and wither
		for (Parameter p: constructors[0].getParameters()) {
			validateConstructorParameter(nodeClass, p);
			validateGetter(nodeClass, p);

			// Recurse to check that the field type itself is valid.
			// For troubleshooting reasons, wrap any thrown exception so the
			// user is able to follow the reference chain.
			try {
				validateType(p.getParameterizedType(), alreadyValidated);
			} catch (InvalidTypeException e) {
				throw new InvalidFieldTypeException(nodeClass, p.getName(), e.getMessage(), e);
			}
		}

		validateFieldsAreFinal(nodeClass);
	}

	/**
	 * We really, really want our types to be immutable. There's no way to truly
	 * prevent people from putting mutable data in an object, but let's work hard to
	 * make sure they don't do it by accident.
	 *
	 * Note that we check every field in the class, including inherited ones; not
	 * just the fields taken by the constructor. Don't use mutable fields in bosk
	 * objects. Just don't!
	 */
	private static void validateFieldsAreFinal(Class<?> nodeClass) throws InvalidFieldTypeException {
		for (Class<?> currentClass = nodeClass; currentClass != null; currentClass = currentClass.getSuperclass()) {
			if (TRUSTED_IMMUTABLE_CLASSES.contains(currentClass)) {
				// Don't bother checking superclasses of trusted immutables
				break;
			}
			for (Field field: currentClass.getDeclaredFields()) {
				if (isStatic(field.getModifiers())) {
					// JaCoCo adds a mutable static field. Anyway, we don't care about static fields.
					continue;
				}
				if (!isFinal(field.getModifiers())) {
					throw new InvalidFieldTypeException(nodeClass, field.getName(), "Field is not final " + fieldDebugInfo(field));
				} else if (field.getType().isArray()){
					throw new InvalidFieldTypeException(nodeClass, field.getName(), "Field is an array, whose entries are not final " + fieldDebugInfo(field));
				}
			}
		}
	}

	private static void validateConstructorParameter(Class<?> containingClass, Parameter parameter) throws InvalidFieldTypeException {
		String fieldName = parameter.getName();
		for (int i = 0; i < fieldName.length(); i++) {
			if (!isValidFieldNameChar(fieldName.codePointAt(i))) {
				throw new InvalidFieldTypeException(containingClass, fieldName, "Only ASCII letters, numbers, and underscores are allowed in field names; illegal character '" + fieldName.charAt(i) + "' at offset " + i);
			}
		}
		if (hasDeserializationPath(containingClass, parameter)) {
			throw new InvalidFieldTypeException(containingClass, fieldName, "@" + DeserializationPath.class.getSimpleName() + " not valid inside the bosk");
		} else if (isEnclosingReference(containingClass, parameter)) {
			Type type = parameter.getParameterizedType();
			if (!Reference.class.isAssignableFrom(rawClass(type))) {
				throw new InvalidFieldTypeException(containingClass, fieldName, "@" + Enclosing.class.getSimpleName() + " applies only to Reference parameters");
			}
			Type referencedType = parameterType(type, Reference.class, 0);
			if (!Entity.class.isAssignableFrom(rawClass(referencedType))) {
				// Not certain this needs to be so strict
				throw new InvalidFieldTypeException(containingClass, fieldName, "@" + Enclosing.class.getSimpleName() + " applies only to References to Entities");
			}
		} else if (isSelfReference(containingClass, parameter)) {
			Type type = parameter.getParameterizedType();
			if (!Reference.class.isAssignableFrom(rawClass(type))) {
				throw new InvalidFieldTypeException(containingClass, fieldName, "@" + Self.class.getSimpleName() + " applies only to References");
			}
			Type referencedType = parameterType(type, Reference.class, 0);
			if (!rawClass(referencedType).isAssignableFrom(containingClass)) {
				throw new InvalidFieldTypeException(containingClass, fieldName, "@" + Self.class.getSimpleName() + " reference to " + rawClass(referencedType).getSimpleName() + " incompatible with containing class " + containingClass.getSimpleName());
			}
		}
	}

	/**
	 * Note that we reserve the character "$", which would otherwise be a valid
	 * character in Java and Javascript field names.
	 */
	private static boolean isValidFieldNameChar(int codePoint) {
		return codePoint == '_'
			|| isBetween('a','z', codePoint)
			|| isBetween('A','Z', codePoint)
			|| isBetween('0','9', codePoint)
			;
	}

	static boolean isBetween(char start, char end, int codePoint) {
		return start <= codePoint && codePoint <= end;
	}

	private static void validateGetter(Class<?> nodeClass, Parameter p) throws InvalidTypeException {
		String fieldName = p.getName();
		Method getter = getterMethod(nodeClass, fieldName);
		if (getter.getParameterCount() != 0) {
			throw new InvalidFieldTypeException(nodeClass, fieldName, "Getter should have no arguments; actually has " + getter.getParameterCount() + " arguments");
		}
		if (!p.getType().equals(getter.getReturnType())) {
			throw new InvalidFieldTypeException(nodeClass, fieldName, "Getter should return " + p.getType().getSimpleName() + "; actually returns " + getter.getReturnType().getSimpleName());
		}
	}

	private static String fieldDebugInfo(Field field) {
		StringBuilder sb = new StringBuilder("{");
		sb.append(Modifier.toString(field.getModifiers()));
		for (Annotation annotation: field.getAnnotations()) {
			sb.append(" ").append(annotation.annotationType().getSimpleName());
		}
		sb.append("}");
		return sb.toString();
	}

	private static final List<Class<?>> SIMPLE_VALUE_CLASSES = asList(
		Number.class, // TODO: This includes classes like AtomicLong which are not immutable!!
		Boolean.class,
		String.class);

	private static final List<Class<?>> TRUSTED_IMMUTABLE_CLASSES = asList(
		Identifier.class,
		Optional.class,
		Phantom.class,
		Catalog.class,
		Listing.class,
		SideTable.class,
		// These ones can be subclassed; hard to REALLY trust them
		Reference.class,
		ListValue.class);

}
