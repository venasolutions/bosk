package io.vena.bosk;

import io.vena.bosk.HookRecorder.Event;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.val;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static io.vena.bosk.HookRecorder.Event.Kind.CHANGED;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class HooksTest extends AbstractBoskTest {
	Bosk<TestRoot> bosk;
	Reference<Identifier> rootIDRef;
	Reference<TestEntity> parentRef;
	Reference<TestChild> child1Ref, child2Ref, child3Ref, anyChildRef;
	CatalogReference<TestChild> childrenRef;
	Reference<String> parentStringRef, child1StringRef, child2StringRef, child3StringRef;
	TestRoot originalRoot;
	TestEntity originalParent;
	TestChild originalChild1, originalChild2, originalChild3;
	HookRecorder recorder;

	@BeforeEach
	void setupBosk() throws InvalidTypeException {
		bosk = setUpBosk(Bosk::simpleDriver);
		rootIDRef = bosk.rootReference().then(Identifier.class, "id");
		parentRef = bosk.rootReference().then(TestEntity.class, TestRoot.Fields.entities, "parent");
		childrenRef = parentRef.thenCatalog(TestChild.class, TestEntity.Fields.children);
		child1Ref = childrenRef.then(Identifier.from("child1"));
		child2Ref = childrenRef.then(Identifier.from("child2"));
		child3Ref = childrenRef.then(Identifier.from("child3"));
		anyChildRef = bosk.reference(TestChild.class, Path.of(
			TestRoot.Fields.entities, "-entity-", TestEntity.Fields.children, "-child-"));
		parentStringRef = parentRef.then(String.class, TestEntity.Fields.string);
		child1StringRef = child1Ref.then(String.class, TestEntity.Fields.string);
		child2StringRef = child2Ref.then(String.class, TestEntity.Fields.string);
		child3StringRef = child3Ref.then(String.class, TestEntity.Fields.string);
		try (val __ = bosk.readContext()) {
			originalRoot = bosk.rootReference().value();
			originalParent = parentRef.value();
			originalChild1 = child1Ref.value();
			originalChild2 = child2Ref.value();
			originalChild3 = child3Ref.value();
		}
		recorder = new HookRecorder();
	}

	@ParameterizedTest
	@EnumSource(Variant.class)
	void testNothingBeforeRegistration(Variant variant) {
		variant.submit.replacement(bosk, child2StringRef, "Too early");
		assertEquals(emptyList(), recorder.events(), "Hook shouldn't see updates unless they are registered");
	}

	/////////////
	//
	// Basic tests: the hooks themselves do nothing but record that they were called.
	//

	@Test
	void testBasic_hooksRunWhenRegistered() {
		bosk.registerHook("child2", child2Ref, recorder.hookNamed("child2"));
		assertEquals(
			singletonList(
				new Event("child2", CHANGED, child2Ref, originalChild2)),
			recorder.events(),
			"Hook should fire when it's registered");
	}

	@ParameterizedTest
	@EnumSource(Variant.class)
	void testBasic_noIrrelevantHooks(Variant variant) {
		bosk.registerHook("child2", child2Ref, recorder.hookNamed("child2"));
		recorder.restart();
		variant.submit.replacement(bosk, child1StringRef, "Child 1 only");
		assertEquals(emptyList(), recorder.events(), "Hook shouldn't see updates for objects that neither enclose nor are enclosed by them");
	}

	@ParameterizedTest
	@EnumSource(Variant.class)
	void testBasic_objectReplacement(Variant variant) {
		registerInterleavedHooks();

		TestChild newChild = originalChild2.withString(originalChild2.string() + " v2");
		variant.submit.replacement(bosk, child2Ref, newChild);

		TestEntity newParent = originalParent.withChildren(originalParent.children().with(newChild));
		checkInterleavedHooks("Hooks for object and parent should fire when object is replaced", newParent, newChild);
	}

	@ParameterizedTest
	@EnumSource(Variant.class)
	void testBasic_fieldReplacement(Variant variant) {
		registerInterleavedHooks();

		String stringV3 = originalChild2.string() + " v3";
		variant.submit.replacement(bosk, child2StringRef, stringV3);

		TestChild newChild = originalChild2.withString(stringV3);
		TestEntity newParent = originalParent.withChildren(originalParent.children().with(newChild));
		checkInterleavedHooks("Hooks for object and parent should fire when field changes", newParent, newChild);
	}

	@ParameterizedTest
	@EnumSource(Variant.class)
	void testBasic_parentReplacementSameChildren(Variant variant) {
		registerInterleavedHooks();

		TestEntity newParent = originalParent.withString(originalParent.string() + " v2");
		variant.submit.replacement(bosk, parentRef, newParent);

		assertEquals(
			asList(
				new Event("parent B", CHANGED, parentRef, newParent),
				new Event("parent D", CHANGED, parentRef, newParent)),
			recorder.events(),
			"Hook for parent (only) should fire when parent changes");
	}

	@ParameterizedTest
	@EnumSource(Variant.class)
	void testBasic_parentReplacementReplacedChild(Variant variant) {
		registerInterleavedHooks();

		String replacement = "replacement";
		TestChild newChild2 = originalChild2.withString(replacement);
		TestEntity newParent = originalParent.withChild(newChild2);
		variant.submit.replacement(bosk, parentRef, newParent);

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

	@ParameterizedTest
	@EnumSource(Variant.class)
	void testBasic_parentReplacementChildDisappearedAndModified(Variant variant) {
		registerInterleavedHooks();

		TestChild newChild1 = originalChild1.withString("replacement1");
		TestChild newChild3 = originalChild3.withString("replacement3");
		TestEntity newParent = originalParent.withChildren(Catalog.of(newChild3, newChild1));
		variant.submit.replacement(bosk, parentRef, newParent);

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

	@ParameterizedTest
	@EnumSource(Variant.class)
	void testBasic_parentCreation(Variant variant) {
		variant.submit.deletion(bosk, parentRef);
		registerInterleavedHooks();
		variant.submit.replacement(bosk, parentRef, originalParent); // Time travel!

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

	@ParameterizedTest
	@EnumSource(Variant.class)
	void testBasic_parentDeletion(Variant variant) {
		registerInterleavedHooks();
		variant.submit.deletion(bosk, parentRef);

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

	@ParameterizedTest
	@EnumSource(Variant.class)
	void testBasic_objectDeletion(Variant variant) {
		registerInterleavedHooks();

		variant.submit.deletion(bosk, child2Ref);
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

	@ParameterizedTest
	@EnumSource(Variant.class)
	void testBasic_nonexistentObjectDeletion(Variant variant) {
		registerInterleavedHooks();

		variant.submit.deletion(bosk, anyChildRef.boundTo(Identifier.from("nonexistent"), Identifier.from("child1")));
		assertEquals(
			emptyList(),
			recorder.events(),
			"No hooks called when deleting a nonexistent object");
	}

	@Test
	void testBasic_initialization() {
		registerInterleavedHooks();

		Identifier child4ID = Identifier.from("child4");
		TestChild newValue = originalChild1
			.withId(child4ID);
		Reference<TestChild> child4Ref = anyChildRef.boundTo(Identifier.from("parent"), child4ID);

		bosk.driver().submitInitialization(child4Ref, newValue);
		TestEntity newParent = originalParent.withChildren(originalParent.children().with(newValue));
		assertEquals(
			asList(
				new Event("parent B", CHANGED, parentRef, newParent),
				new Event("parent D", CHANGED, parentRef, newParent),
				new Event("Any child", CHANGED, child4Ref, newValue)),
			recorder.events(),
			"Hooks for parent and child should fire when a child is initialized");
	}

	@Test
	void testBasic_reinitialization() {
		registerInterleavedHooks();

		TestChild newValue = originalChild1
			.withString("replacement");

		bosk.driver().submitInitialization(child1Ref, newValue);

		assertEquals(
			emptyList(),
			recorder.events(),
			"No hooks when initializing an object that already exists");
	}

	@Test
	void testBasic_initializationInNonexistentParent() {
		registerInterleavedHooks();

		Identifier child4ID = Identifier.from("child4");
		TestChild newValue = originalChild1
			.withId(child4ID);
		Reference<TestChild> child4Ref = anyChildRef.boundTo(Identifier.from("nonexistent"), child4ID);

		bosk.driver().submitInitialization(child4Ref, newValue);

		assertEquals(
			emptyList(),
			recorder.events(),
			"No hooks when initializing an object inside a nonexistent parent");
	}

	/**
	 * Provides a good test that hooks are run in registration order.
	 */
	private void registerInterleavedHooks() {
		bosk.registerHook("child2 A", child2Ref, recorder.hookNamed("child2 A"));
		bosk.registerHook("parent B", parentRef, recorder.hookNamed("parent B"));
		bosk.registerHook("child2 C", child2Ref, recorder.hookNamed("child2 C"));
		bosk.registerHook("parent D", parentRef, recorder.hookNamed("parent D"));
		bosk.registerHook("Any child", anyChildRef, recorder.hookNamed("Any child"));
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
	void testNested_breadthFirst() throws IOException, InterruptedException {
		AtomicBoolean initializing = new AtomicBoolean(true);

		// Child 1 update triggers A and B, and A triggers C

		bosk.registerHook("A", child1StringRef, recorder.hookNamed("A", ref -> {
			if (initializing.get()) {
				assertEquals("child1", ref.value(),
					"Upon registration, hooks runs on initial state");
				return;
			}

			assertEquals("newValue", ref.value(),
				"Update that triggered the hook is visible");
			assertEquals("child2", child2StringRef.value(),
				"Subsequent change to child2 is not visible");
			assertEquals("child3", child3StringRef.value(),
				"Subsequent change to child3 is not visible");
			bosk.driver().submitReplacement(child2StringRef, Optional.of(ref.value() + "_child2_hookA"));
		}));
		bosk.registerHook("B", child1StringRef, recorder.hookNamed("B", ref -> {
			if (initializing.get()) {
				assertEquals("child1", ref.value(),
					"Upon registration, hooks runs on initial state");
				return;
			}

			assertEquals("newValue", ref.value(),
				"Update that triggered the hook is visible");
			assertEquals("child2", child2StringRef.value(),
				"A's update to child2 is not visible even though A runs first");
			assertEquals("child3", child3StringRef.value(),
				"Subsequent change to child3 is not visible");
			bosk.driver().submitReplacement(child3StringRef, Optional.of(ref.value() + "_child3_hookB"));
		}));
		bosk.registerHook("C", child2StringRef, recorder.hookNamed("C", ref -> {
			if (initializing.get()) {
				assertEquals("child2", ref.value(),
					"Upon registration, hooks runs on initial state");
				return;
			}

			assertEquals("newValue_child2_hookA", ref.value(),
				"Update that triggered the hook is visible");
			assertEquals("newValue", child1StringRef.value(),
				"Prior update still visible");
			assertEquals("child3", child3StringRef.value(),
				"B's update to child3 is not visible even though B runs first");
		}));

		// Reset everything and submit the replacement that triggers the hooks
		recorder.restart();
		initializing.set(false);
		bosk.driver().submitReplacement(child1StringRef, Optional.of("newValue"));
		bosk.driver().flush();

		// Check for expected hook events
		List<Event> expectedEvents = asList(
			new Event("A", CHANGED, child1StringRef, "newValue"),
			new Event("B", CHANGED, child1StringRef, "newValue"),
			new Event("C", CHANGED, child2StringRef, "newValue_child2_hookA")
		);

		assertEquals(
			expectedEvents,
			recorder.events());

	}

	@Test
	void testNestedMultipleUpdates_breadthFirst() {
		// Register hooks to propagate string updates from parent -> child 1 -> 2 -> 3 with a tag
		bosk.registerHook("+P", parentStringRef, recorder.hookNamed("P", ref -> {
			bosk.driver().submitReplacement(child1StringRef, Optional.of(ref.value() + "+P"));
			bosk.driver().submitReplacement(child2StringRef, Optional.of(ref.value() + "+P"));
			bosk.driver().submitReplacement(child3StringRef, Optional.of(ref.value() + "+P"));
		}));
		bosk.registerHook("+C1", child1StringRef, recorder.hookNamed("C1", ref -> {
			bosk.driver().submitReplacement(child2StringRef, Optional.of(ref.value() + "+C1"));
			bosk.driver().submitReplacement(child3StringRef, Optional.of(ref.value() + "+C1"));
		}));
		bosk.registerHook("+C2", child2StringRef, recorder.hookNamed("C2", ref -> {
			bosk.driver().submitReplacement(child3StringRef, Optional.of(ref.value() + "+C2"));
		}));
		bosk.registerHook("C3", child3StringRef, recorder.hookNamed("C3"));

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
		bosk.driver().submitReplacement(parentStringRef, Optional.of("replacement"));

		assertEquals(
			expectedEvents,
			recorder.events(),
			"All hooks for an update should be called before any hooks for subsequent updates");

		try (val __ = bosk.readContext()) {
			assertEquals(expectedParent, parentRef.value());
		}
	}

	@Test
	void testNested_correctReadContext() {
		bosk.registerHook("stringCopier", child2Ref, recorder.hookNamed("stringCopier", ref ->
			bosk.driver().submitReplacement(child1StringRef, Optional.of(ref.value().string()))));
		recorder.restart();
		String expectedString = "expected string";

		try (val __ = bosk.readContext()) {
			bosk.driver().submitReplacement(child2StringRef, Optional.of(expectedString));
			// If the hook were to run accidentally in this ReadContext, it would
			// see originalChild2.string() instead of expectedString.
		}

		assertEquals(
			singletonList(new Event("stringCopier", CHANGED, child2Ref, originalChild2.withString(expectedString))),
			recorder.events(),
			"Hooks run in the right ReadContext regardless of active read scope at submission or execution time");


		try (val __ = bosk.readContext()) {
			assertEquals(expectedString, child2StringRef.value(), "Correct value got copied");
		}
	}

	interface Submit {
		<T> void replacement(Bosk<?> bosk, Reference<T> target, T newValue);
		<T> void deletion(Bosk<?> bosk, Reference<T> target);
	}

	/**
	 * Every conditional operation has the same effect as the corresponding
	 * unconditional operation if the precondition is satisfied.
	 * Thus, every test is run using both.
	 */
	enum Variant {
		UNCONDITIONAL(new Submit() {
			@Override
			public <T> void replacement(Bosk<?> bosk, Reference<T> target, T newValue) {
				bosk.driver().submitReplacement(target, Optional.of(newValue));
			}

			@Override
			public <T> void deletion(Bosk<?> bosk, Reference<T> target) {
				bosk.driver().submitDeletion(target);
			}
		}),
		CONDITIONAL(new Submit() {
			@Override
			public <T> void replacement(Bosk<?> bosk, Reference<T> target, T newValue) {
				bosk.driver().submitConditionalReplacement(target, newValue, rootIDReference(bosk), rootID());
			}

			@Override
			public <T> void deletion(Bosk<?> bosk, Reference<T> target) {
				bosk.driver().submitConditionalDeletion(target, rootIDReference(bosk), rootID());
			}

			private Reference<Identifier> rootIDReference(Bosk<?> bosk) {
				try {
					return bosk.rootReference().then(Identifier.class, "id");
				} catch (InvalidTypeException e) {
					throw new AssertionError(e);
				}
			}

			private Identifier rootID() {
				return Identifier.from("root");
			}
		});


		private final Submit submit;

		Variant(Submit submit) {
			this.submit = submit;
		}
	}
}
