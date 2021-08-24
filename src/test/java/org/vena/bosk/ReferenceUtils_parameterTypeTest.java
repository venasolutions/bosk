package org.vena.bosk;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.vena.bosk.ReferenceUtils.parameterType;
import static org.vena.bosk.ReferenceUtils.rawClass;
import static org.vena.bosk.util.Types.parameterizedType;

class ReferenceUtils_parameterTypeTest extends AbstractBoskTest {

	@Test
	void directCases() {
		assertParameter0IsString(parameterizedType(Class1.class, String.class));
		assertParameter0IsString(parameterizedType(Interface1.class, String.class));
		assertParameter0IsString(parameterizedType(GenericExtendingClass1.class, String.class));
		assertParameter0IsString(parameterizedType(GenericImplementingInterface1.class, String.class));
		assertParameter0IsString(parameterizedType(GenericExtendingInterface1.class, String.class));

		assertDirectParameterEquals(Integer.class, parameterizedType(GenericExtendingClass2OfString.class, Integer.class), 0);
		assertDirectParameterEquals(Integer.class, parameterizedType(GenericImplementingInterface2OfString.class, Integer.class), 0);
		assertDirectParameterEquals(Integer.class, parameterizedType(GenericExtendingInterface2OfString.class, Integer.class), 0);

		assertDirectParameterEquals(Class1.class, parameterizedType(TwoParameters.class, Class1.class, Class2.class), 0);
		assertDirectParameterEquals(Class2.class, parameterizedType(TwoParameters.class, Class1.class, Class2.class), 1);
	}

	void assertParameter0IsString(Type type) {
		assertDirectParameterEquals(String.class, type, 0);

	}
	void assertDirectParameterEquals(Type expected, Type type, int index) {
		assertParameterTypeEquals(expected, type, rawClass(type), index);
	}

	@Test
	void inheritedParameters() {
		assertParameterTypeIsString(ClassExtendingClass1.class, Class1.class, 0);
		assertParameterTypeIsString(ClassImplementingInterface1.class, Interface1.class, 0);
		assertParameterTypeIsString(InterfaceExtendingInterface1.class, Interface1.class, 0);

	}

	@Test
	void passthruParameters() {
		assertParameterTypeIsString(parameterizedType(GenericExtendingClass1.class, String.class), Class1.class, 0);
		assertParameterTypeIsString(parameterizedType(GenericImplementingInterface1.class, String.class), Interface1.class, 0);
		assertParameterTypeIsString(parameterizedType(GenericExtendingInterface1.class, String.class), Interface1.class, 0);
	}

	@Test
	void classIneritingLots() {
		assertParameterTypeEquals(Integer.class, ClassInheritingLots.class, Class1.class, 0);
		assertParameterTypeEquals(Long.class, ClassInheritingLots.class, GenericExtendingInterface1.class, 0);
		assertParameterTypeEquals(Long.class, ClassInheritingLots.class, Interface1.class, 0);
		assertParameterTypeEquals(Float.class, ClassInheritingLots.class, GenericExtendingInterface2OfString.class, 0);
		assertParameterTypeEquals(String.class, ClassInheritingLots.class, Interface2.class, 0);
	}

	@Test
	void switcheroo() {
		assertParameterTypeEquals(Class2.class, parameterizedType(Switcheroo.class, Class1.class, Class2.class), TwoParameters.class, 0);
		assertParameterTypeEquals(Class1.class, parameterizedType(Switcheroo.class, Class1.class, Class2.class), TwoParameters.class, 1);
	}

	@Test
	void errorCases() {
		assertThrows(AssertionError.class, () -> parameterType(ClassInheritingLots.class, ArrayList.class, 0));
		assertThrows(AssertionError.class, () -> parameterType(ClassInheritingLots.class, List.class, 0));
	}

	private static class Class1<T> {}
	private static class Class2<T> {}
	private interface Interface1<T> {}
	private interface Interface2<T> {}

	private static class ClassExtendingClass1 extends Class1<String> {}
	private static class ClassImplementingInterface1 implements Interface1<String> {}
	private interface InterfaceExtendingInterface1 extends Interface1<String> {}

	private static class GenericExtendingClass1<T> extends Class1<T> {}
	private static class GenericImplementingInterface1<T> implements Interface1<T> {}
	private interface GenericExtendingInterface1<T> extends Interface1<T> {}
	private static class GenericExtendingClass2OfString<T> extends Class2<String> {}
	private static class GenericImplementingInterface2OfString<T> implements Interface2<String> {}
	private interface GenericExtendingInterface2OfString<T> extends Interface2<String> {}

	private static class ClassInheritingLots extends Class1<Integer> implements GenericExtendingInterface1<Long>, GenericExtendingInterface2OfString<Float> {}

	private interface TwoParameters<A,B> {}
	private interface Switcheroo<A,B> extends TwoParameters<B,A> {}

	private void assertParameterTypeIsString(Type type, Class<?> genericClass, int index) {
		assertEquals(String.class, parameterType(type, genericClass, index));
	}

	private void assertParameterTypeEquals(Type expected, Type type, Class<?> genericClass, int index) {
		assertEquals(expected, parameterType(type, genericClass, index));
	}

}
