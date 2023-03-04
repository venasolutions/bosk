package io.vena.bosk;

import io.vena.bosk.annotations.ReferencePath;
import io.vena.bosk.exceptions.InvalidTypeException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class BuildReferencesErrorTest extends AbstractBoskTest {
	static Bosk<TestRoot> bosk;
	static TestEntityBuilder teb;

	@BeforeAll
	static void setup() throws InvalidTypeException {
		bosk = setUpBosk(Bosk::simpleDriver);
		teb = new TestEntityBuilder(bosk);
	}

	@Test
	void thisIsForPITest() throws InvalidTypeException {
		// pitest counts initialization failures as a mutation surviving, which is annoying.
		// By doing the setup here in a test method, we ensure the mutations are killed.
		bosk.buildReferences(BuildReferencesTest.Refs.class);
	}

	@Test
	void returnsNonReference_throws() {
		// We're not all that particular about which exception gets thrown
		assertThrows(InvalidTypeException.class, ()->
			bosk.buildReferences(Invalid_NonReference.class));
	}

	@Test
	void missingAnnotation_throws() {
		assertThrows(InvalidTypeException.class, ()->
			bosk.buildReferences(Invalid_NoAnnotation.class));
	}

	@Test
	void wrongReturnType_throws() {
		assertThrows(InvalidTypeException.class, ()->
			bosk.buildReferences(Invalid_WrongType.class));
	}

	@Test
	void unexpectedParameterType_throws() {
		assertThrows(InvalidTypeException.class, ()->
			bosk.buildReferences(Invalid_WeirdParameter.class));
	}

	public interface Invalid_NonReference {
		@ReferencePath("/entities/-entity-")
		String anyEntity();
	}

	public interface Invalid_NoAnnotation {
		Reference<TestEntity> anyEntity();
	}

	public interface Invalid_WrongType {
		@ReferencePath("/entities/-entity-")
		Reference<TestChild> anyEntity();
	}

	public interface Invalid_WeirdParameter {
		@ReferencePath("/entities/-entity-")
		Reference<TestEntity> anyEntity(Object parameter);
	}

}
