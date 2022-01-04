package org.vena.bosk;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vena.bosk.Bosk.ReadContext;
import org.vena.bosk.HookRecorder.Event;
import org.vena.bosk.exceptions.InvalidTypeException;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.vena.bosk.HookRecorder.Event.Kind.CHANGED;

public class HooksTest extends AbstractBoskTest {
	Bosk<TestRoot> bosk;
	Reference<TestEntity> parentRef;
	Reference<TestChild> child1Ref, child2Ref, child3Ref, anyChildRef;
	CatalogReference<TestChild> childrenRef;
	Reference<String> parentStringRef, child1StringRef, child2StringRef, child3StringRef;
	TestEntity originalParent;
	TestChild originalChild1, originalChild2, originalChild3;
	HookRecorder recorder;

	@BeforeEach
	void setupBosk() throws InvalidTypeException {
		bosk = setUpBosk(Bosk::simpleDriver);
		parentRef = bosk.rootReference().then(TestEntity.class, TestRoot.Fields.entities, "parent");
		childrenRef = parentRef.thenCatalog(TestChild.class, TestEntity.Fields.children);
		child1Ref = childrenRef.then(Identifier.from("child1"));
		child2Ref = childrenRef.then(Identifier.from("child2"));
		child3Ref = childrenRef.then(Identifier.from("child3"));
		anyChildRef = childrenRef.then(TestChild.class, "-child-");
		parentStringRef = parentRef.then(String.class, TestEntity.Fields.string);
		child1StringRef = child1Ref.then(String.class, TestEntity.Fields.string);
		child2StringRef = child2Ref.then(String.class, TestEntity.Fields.string);
		child3StringRef = child3Ref.then(String.class, TestEntity.Fields.string);
		try (ReadContext context = bosk.readContext()) {
			originalParent = parentRef.value();
			originalChild1 = child1Ref.value();
			originalChild2 = child2Ref.value();
			originalChild3 = child3Ref.value();
		}
		recorder = new HookRecorder();
	}

	@Test
	void testNothingBeforeRegistration() throws InvalidTypeException {
		bosk.driver().submitReplacement(child2StringRef, "Too early");
		assertEquals(emptyList(), recorder.events(), "Hook shouldn't see updates unless they are registered");
	}

	/////////////
	//
	// Basic tests: the hooks themselves do nothing but record that they were called.
	//

	@Test
	void testBasic_NoIrrelevantHooks() throws InvalidTypeException {
		bosk.registerHook(child2Ref, recorder.hookNamed("child2"));
		recorder.restart();
		bosk.driver().submitReplacement(child1StringRef, "Child 1 only");
		assertEquals(emptyList(), recorder.events(), "Hook shouldn't see updates for objects that neither enclose nor are enclosed by them");
	}

	@Test
	void testBasic_objectReplacement() throws InvalidTypeException {
		registerInterleavedHooks();

		TestChild newChild = originalChild2.withString(originalChild2.string() + " v2");
		bosk.driver().submitReplacement(child2Ref, newChild);

		TestEntity newParent = originalParent.withChildren(originalParent.children().with(newChild));
		checkInterleavedHooks("Hooks for object and parent should fire when object is replaced", newParent, newChild);
	}

	@Test
	void testBasic_fieldReplacement() throws InvalidTypeException {
		registerInterleavedHooks();

		String stringV3 = originalChild2.string() + " v3";
		bosk.driver().submitReplacement(child2StringRef, stringV3);

		TestChild newChild = originalChild2.withString(stringV3);
		TestEntity newParent = originalParent.withChildren(originalParent.children().with(newChild));
		checkInterleavedHooks("Hooks for object and parent should fire when field changes", newParent, newChild);
	}

	@Test
	void testBasic_parentReplacementSameChildren() throws InvalidTypeException {
		registerInterleavedHooks();

		TestEntity newParent = originalParent.withString(originalParent.string() + " v2");
		bosk.driver().submitReplacement(parentRef, newParent);

		assertEquals(
				asList(
						new Event("parent B", CHANGED, parentRef, newParent),
						new Event("parent D", CHANGED, parentRef, newParent)),
				recorder.events(),
				"Hook for parent (only) should fire when parent changes");
	}

	@Test
	void testBasic_parentReplacementReplacedChild() throws InvalidTypeException {
		registerInterleavedHooks();

		String replacement = "replacement";
		TestChild newChild2 = originalChild2.withString(replacement);
		TestEntity newParent = originalParent.withChild(newChild2);
		bosk.driver().submitReplacement(parentRef, newParent);

		assertEquals(
				asList(
						new Event("child2 A", CHANGED, child2Ref, newChild2),
						new Event("parent B", CHANGED, parentRef, newParent),
						new Event("child2 C", CHANGED, child2Ref, newChild2),
						new Event("parent D", CHANGED, parentRef, newParent),
						new Event("Any child", CHANGED, child2Ref, newChild2)),
				recorder.events(),
				"Hook for object and parent should fire when parent gets new child");
	}

	@Test
	void testBasic_parentReplacementChildDisappearedAndModified() throws InvalidTypeException {
		registerInterleavedHooks();

		TestChild newChild1 = originalChild1.withString("replacement1");
		TestChild newChild3 = originalChild3.withString("replacement3");
		TestEntity newParent = originalParent.withChildren(Catalog.of(newChild3, newChild1));
		bosk.driver().submitReplacement(parentRef, newParent);

		assertEquals(
				asList(
						new Event("child2 A", CHANGED, child2Ref, null),
						new Event("parent B", CHANGED, parentRef, newParent),
						new Event("child2 C", CHANGED, child2Ref, null),
						new Event("parent D", CHANGED, parentRef, newParent),

						// For a given hook, absent objects are processed first, in reverse order of their
						// appearance in the prior state
						new Event("Any child", CHANGED, child2Ref, null),

						// Updated objects are processed next, in order of their appearance in the new state
						new Event("Any child", CHANGED, child3Ref, newChild3),
						new Event("Any child", CHANGED, child1Ref, newChild1)),
				recorder.events(),
				"Hook for objects and parent should fire when parent gets new child catalog");
	}

	@Test
	void testBasic_parentCreation() throws InvalidTypeException {
		bosk.driver().submitDeletion(parentRef);
		registerInterleavedHooks();
		bosk.driver().submitReplacement(parentRef, originalParent); // Time travel!

		assertEquals(
				asList(
						new Event("child2 A", CHANGED, child2Ref, originalChild2),
						new Event("parent B", CHANGED, parentRef, originalParent),
						new Event("child2 C", CHANGED, child2Ref, originalChild2),
						new Event("parent D", CHANGED, parentRef, originalParent),

						new Event("Any child", CHANGED, child1Ref, originalChild1),
						new Event("Any child", CHANGED, child2Ref, originalChild2),
						new Event("Any child", CHANGED, child3Ref, originalChild3)),
				recorder.events(),
				"Hook for objects and parent should fire when parent gets created");
	}

	@Test
	void testBasic_parentDeletion() throws InvalidTypeException {
		registerInterleavedHooks();
		bosk.driver().submitDeletion(parentRef);

		assertEquals(
				asList(
						new Event("child2 A", CHANGED, child2Ref, null),
						new Event("parent B", CHANGED, parentRef, null),
						new Event("child2 C", CHANGED, child2Ref, null),
						new Event("parent D", CHANGED, parentRef, null),

						// For a given hook, catalog entries are processed in reverse order
						new Event("Any child", CHANGED, child3Ref, null),
						new Event("Any child", CHANGED, child2Ref, null),
						new Event("Any child", CHANGED, child1Ref, null)),
				recorder.events(),
				"Hook for objects and parent should fire when parent gets deleted");
	}

	@Test
	void testBasic_objectDeletion() throws InvalidTypeException {
		registerInterleavedHooks();

		bosk.driver().submitDeletion(child2Ref);
		TestEntity newParent = originalParent.withChildren(originalParent.children().without(originalChild2));
		assertEquals(
			asList(
				new Event("child2 A", CHANGED, child2Ref, null),
				new Event("parent B", CHANGED, parentRef, newParent),
				new Event("child2 C", CHANGED, child2Ref, null),
				new Event("parent D", CHANGED, parentRef, newParent),
				new Event("Any child", CHANGED, child2Ref, null)),
			recorder.events(),
			"Hooks for parent and child should fire when an object is deleted");
	}

	/**
	 * Provides a good test that hooks are run in registration order.
	 */
	private void registerInterleavedHooks() {
		bosk.registerHook(child2Ref, recorder.hookNamed("child2 A"));
		bosk.registerHook(parentRef, recorder.hookNamed("parent B"));
		bosk.registerHook(child2Ref, recorder.hookNamed("child2 C"));
		bosk.registerHook(parentRef, recorder.hookNamed("parent D"));
		bosk.registerHook(anyChildRef, recorder.hookNamed("Any child"));
		recorder.restart();
	}

	private void checkInterleavedHooks(String message, TestEntity newParent, TestChild newChild2) {
		assertEquals(
			asList(
				new Event("child2 A", CHANGED, child2Ref, newChild2),
				new Event("parent B", CHANGED, parentRef, newParent),
				new Event("child2 C", CHANGED, child2Ref, newChild2),
				new Event("parent D", CHANGED, parentRef, newParent),
				new Event("Any child", CHANGED, child2Ref, newChild2)),
			recorder.events(),
			message);
	}

	/////////////
	//
	// Nested tests: the hooks themselves submit bosk updates
	//

	@Test
	void testNested_breadthFirst() {
		// Register hooks to propagate string updates from parent -> child 1 -> 2 -> 3 with a tag
		bosk.registerHook(parentStringRef, recorder.hookNamed("P", ref -> {
			bosk.driver().submitReplacement(child1StringRef, ref.value() + "+P");
			bosk.driver().submitReplacement(child2StringRef, ref.value() + "+P");
			bosk.driver().submitReplacement(child3StringRef, ref.value() + "+P");
		}));
		bosk.registerHook(child1StringRef, recorder.hookNamed("C1", ref -> {
			bosk.driver().submitReplacement(child2StringRef, ref.value() + "+C1");
			bosk.driver().submitReplacement(child3StringRef, ref.value() + "+C1");
		}));
		bosk.registerHook(child2StringRef, recorder.hookNamed("C2", ref -> {
			bosk.driver().submitReplacement(child3StringRef, ref.value() + "+C2");
		}));
		bosk.registerHook(child3StringRef, recorder.hookNamed("C3"));

		List<Event> expectedEvents = asList(
			new Event("P", CHANGED, parentStringRef, "replacement"),
			// P triggers C1, C2, C3
			// C1 triggers C2, C3
			// C2 triggers C3 (caused by P)
			// C2 triggers C3 (caused by C1)
			// So C2 changes twice and C3 changes four times
			new Event("C1", CHANGED, child1StringRef, "replacement+P"),
			new Event("C2", CHANGED, child2StringRef, "replacement+P"),
			new Event("C3", CHANGED, child3StringRef, "replacement+P"),
			new Event("C2", CHANGED, child2StringRef, "replacement+P+C1"),
			new Event("C3", CHANGED, child3StringRef, "replacement+P+C1"),
			new Event("C3", CHANGED, child3StringRef, "replacement+P+C2"), // This is interesting if you ponder it
			new Event("C3", CHANGED, child3StringRef, "replacement+P+C1+C2")
		);
		TestEntity expectedParent = originalParent.withString("replacement").withChildren(Catalog.of(
				originalChild1.withString("replacement+P"),
				originalChild2.withString("replacement+P+C1"),
				originalChild3.withString("replacement+P+C1+C2")
		));

		recorder.restart();
		bosk.driver().submitReplacement(parentStringRef, "replacement");

		assertEquals(
			expectedEvents,
			recorder.events(),
			"All hooks for an update should be called before any hooks for subsequent updates");

		try (ReadContext context = bosk.readContext()) {
			assertEquals(expectedParent, parentRef.value());
		}
	}

	@Test
	void testNested_correctReadContext() {
		bosk.registerHook(child2Ref, recorder.hookNamed("stringCopier", ref ->
			bosk.driver().submitReplacement(child1StringRef, ref.value().string())));
		recorder.restart();
		String expectedString = "expected string";

		try (ReadContext bogusContext = bosk.readContext()) {
			bosk.driver().submitReplacement(child2StringRef, expectedString);
			// If the hook were to run accidentally in this ReadContext, it would
			// see originalChild2.string() instead of expectedString.
		}

		assertEquals(
				asList(new Event("stringCopier", CHANGED, child2Ref, originalChild2.withString(expectedString))),
				recorder.events(),
				"Hooks run in the right ReadContext regardless of active read scope at submission or execution time");


		try (ReadContext context = bosk.readContext()) {
			assertEquals(expectedString, child2StringRef.value(), "Correct value got copied");
		}
	}
}
