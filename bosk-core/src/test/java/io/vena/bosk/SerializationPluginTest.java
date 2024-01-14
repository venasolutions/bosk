package io.vena.bosk;

import io.vena.bosk.annotations.Self;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

import static io.vena.bosk.ReferenceUtils.theOnlyConstructorFor;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO: This should aim for full coverage of SerializationPlugin
class SerializationPluginTest {

	@Test
	void inheritedFieldAttribute_works() {
		Constructor<Child> childConstructor = theOnlyConstructorFor(Child.class);
		Parameter selfParameter = childConstructor.getParameters()[0];
		assertTrue(SerializationPlugin.isSelfReference(Child.class, selfParameter));
	}

	@RequiredArgsConstructor
	@Getter
	static class Parent {
		@Self final Parent self;
	}

	@Getter
	static class Child extends Parent {
		public Child(Parent self) {
			super(self);
		}
	}
}