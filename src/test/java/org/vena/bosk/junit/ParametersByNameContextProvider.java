package org.vena.bosk.junit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.platform.commons.util.ReflectionUtils;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

public class ParametersByNameContextProvider implements TestTemplateInvocationContextProvider {

	@Override
	public boolean supportsTestTemplate(ExtensionContext context) {
		return context.getTestMethod().isPresent();
	}

	@Override
	public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
		ParametersByName annotationFromMethod = context.getTestMethod().map(m -> m.getAnnotation(ParametersByName.class)).orElse(null);
		Class<?> testClass = context.getRequiredTestClass();
		List<String> parameterNames = allParameterNames(context);
		Map<String, List<?>> valueListsByName = new LinkedHashMap<>();
		parameterNames.forEach(name ->
			valueListsByName.put(name, invokeCorrespondingMethod(name, testClass).collect(toList())));
		Stream<Map<String, ?>> parameterBindings = cartesianProduct(valueListsByName);
		List<Map<String, ?>> debug = parameterBindings.collect(toList());

		if (annotationFromMethod != null && annotationFromMethod.singleInvocationIndex() != 0) {
			int singleInvocationIndex = annotationFromMethod.singleInvocationIndex(); // counts from 1
			Map<String, ?> binding = debug.stream()
				.skip(singleInvocationIndex-1).limit(1).findAny()
				.orElseThrow(()->new ParameterResolutionException("Invalid invocation index: " + singleInvocationIndex));
			return Stream.of(new TestTemplateInvocationContext() {
				@Override
				public String getDisplayName(int invocationIndex) {
					return "[" + singleInvocationIndex + "] " + binding.toString();
				}

				@Override
				public List<Extension> getAdditionalExtensions() {
					return singletonList(new ParameterBinder(binding));
				}
			});
		} else {
			return debug.stream().map(binding -> new TestTemplateInvocationContext() {
				@Override
				public String getDisplayName(int invocationIndex) {
					return "[" + invocationIndex + "] " + binding.toString();
				}

				@Override
				public List<Extension> getAdditionalExtensions() {
					return singletonList(new ParameterBinder(binding));
				}
			});
		}
	}

	private List<String> allParameterNames(ExtensionContext context) {
		List<Parameter> parameters = new ArrayList<>();

		// Parameters from constructor
		Class<?> testClass = context.getRequiredTestClass();
		List<Constructor<?>> annotatedConstructors = ReflectionUtils.findConstructors(testClass, c -> c.isAnnotationPresent(ParametersByName.class));
		switch (annotatedConstructors.size()) {
			case 0:
				break;
			case 1:
				Collections.addAll(parameters, annotatedConstructors.get(0).getParameters());
				break;
			default:
				throw new ParameterResolutionException("Multiple constructors annotated with " + ParametersByName.class.getSimpleName() + ": " + annotatedConstructors);
		}

		// Parameters from test method
		Collections.addAll(parameters, context.getRequiredTestMethod().getParameters());

		// Remove dupes if any
		return parameters.stream()
			.map(Parameter::getName)
			.distinct()
			.collect(toList());
	}

	private Stream<?> invokeCorrespondingMethod(String name, Class<?> testClass) {
		Method m = ReflectionUtils.getRequiredMethod(testClass, name);
		return (Stream<?>) ReflectionUtils.invokeMethod(m, null);
	}

	private static Stream<Map<String, ?>> cartesianProduct(Map<String, List<?>> parameterListsByName) {
		Stream<Map<String, ?>> result = Stream.of(emptyMap());
		for (Map.Entry<String, List<?>> entry: parameterListsByName.entrySet()) {
			String name = entry.getKey();
			List<?> values = entry.getValue();
			result = result.flatMap(existingMap ->
				values.stream().map(newValue -> {
					Map<String, Object> newMap = new LinkedHashMap<>(existingMap);
					newMap.put(name, newValue);
					return newMap;
				})
			);
		}
		return result;
	}

	private static class ParameterBinder implements ParameterResolver {
		private final Map<String, ?> binding;

		public ParameterBinder(Map<String, ?> binding) {
			this.binding = binding;
		}

		@Override
		public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
			return binding.containsKey(parameterContext.getParameter().getName());
		}

		@Override
		public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
			return binding.get(parameterContext.getParameter().getName());
		}
	}
}
