package io.vena.bosk;

import io.vena.bosk.annotations.ReferencePath;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
class HookRegistrar {
	@SuppressWarnings({"unchecked","rawtypes"})
	static <T> void registerHooks(T receiverObject, Bosk<?> bosk) throws InvalidTypeException {
		Class<?> receiverClass = receiverObject.getClass();
		for (Method method: receiverClass.getDeclaredMethods()) { // TODO: Inherited methods
			ReferencePath referencePath = method.getAnnotation(ReferencePath.class);
			if (referencePath == null) {
				continue;
			}
			Path path = Path.parseParameterized(referencePath.value());
			Reference<Object> scope = bosk.rootReference().then(Object.class, path);
			List<Function<Reference<?>, Object>> argumentFunctions = new ArrayList<>(method.getParameterCount());
			argumentFunctions.add(ref -> receiverObject); // The "this" pointer
			for (Parameter p: method.getParameters()) {
				if (p.getType().isAssignableFrom(Reference.class)) {
					if (ReferenceUtils.parameterType(p.getParameterizedType(), Reference.class, 0).equals(scope.targetType())) {
						argumentFunctions.add(ref -> ref);
					} else {
						throw new InvalidTypeException("Expected reference to " + scope.targetType() + ": " + method.getName() + " parameter " + p.getName());
					}
				} else if (p.getType().isAssignableFrom(BindingEnvironment.class)) {
					argumentFunctions.add(ref -> scope.parametersFrom(ref.path()));
				} else {
					throw new InvalidTypeException("Unsupported parameter type " + p.getType() + ": " + method.getName() + " parameter " + p.getName());
				}
			}
			 MethodHandle hook;
			 try {
				  hook = MethodHandles.lookup().unreflect(method);
			 } catch (IllegalAccessException e) {
				  throw new IllegalArgumentException(e);
			 }
			 bosk.registerHook(method.getName(), scope, ref -> {
				  try {
					  List<Object> arguments = new ArrayList<>(argumentFunctions.size());
					  argumentFunctions.forEach(f -> arguments.add(f.apply(ref)));
					  hook.invokeWithArguments(arguments);
				  } catch (Throwable e) {
					  throw new IllegalStateException("Unable to call hook \"" + method.getName() + "\"", e);
				  }
			 });
		}

	}
}
