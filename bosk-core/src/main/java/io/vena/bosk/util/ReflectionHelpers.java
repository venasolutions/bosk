package io.vena.bosk.util;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;

import static java.lang.reflect.Modifier.isPrivate;

public final class ReflectionHelpers {

	public static Field setAccessible(Field field) {
		makeAccessible(field, field.getModifiers());
		return field;
	}

	public static <T extends Executable> T setAccessible(T method) {
		makeAccessible(method, method.getModifiers());
		return method;
	}

	private static void makeAccessible(AccessibleObject object, int modifiers) {
		// Let's honour "private" modifiers so people can know that private
		// methods and fields aren't being called by us. That allows them to
		// refactor them freely without concern for breaking some Bosk magic.
		//
		if (isPrivate(modifiers)) {
			throw new IllegalArgumentException("Access to private " + object.getClass().getSimpleName() + " is forbidden: " + object);
		}

		//... but otherwise, it's open season.
		object.setAccessible(true);
	}

}
