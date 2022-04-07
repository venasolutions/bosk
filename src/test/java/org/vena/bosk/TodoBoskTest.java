package org.vena.bosk;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.With;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;
import lombok.val;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.vena.bosk.TodoBoskTest.TodoItem.Status;
import org.vena.bosk.drivers.BufferingDriver;
import org.vena.bosk.exceptions.InvalidTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.vena.bosk.ListingEntry.LISTING_ENTRY;

/**
 * A very ad hoc test that's gradually being replaced by the more systematic {@link BoskTest}.
 *
 * @author Patrick Doyle
 */
@RequiredArgsConstructor
public class TodoBoskTest extends AbstractRoundTripTest {

	/////////////////////////
	//
	// Todolist testing
	//

	Identifier listID;
	Bosk<TodoList> bosk;
	BoskDriver<TodoList> boskDriver;
	Reference<TodoList> listRef;
	Identifier item1ID;
	Reference<TodoItem> item1;
	CatalogReference<TodoItem> items;
	Reference<MoreInfo> moreInfoRef;
	SideTableReference<TodoItem,String> ownersRef;

	public void initialize(DriverFactory<TodoList> driverFactory) throws InvalidTypeException {
		listID = Identifier.from("todoList1");
		bosk = new Bosk<>("Todo bosk", TodoList.class, new TodoList(listID, Catalog.empty(), Optional.empty()), driverFactory);
		boskDriver = bosk.driver();
		listRef = bosk.rootReference();
		items = listRef.thenCatalog(TodoItem.class, "items");
		item1ID = Identifier.from("item1");
		item1 = items.then(item1ID);
		moreInfoRef = listRef.then(MoreInfo.class, "moreInfo");
		ownersRef = moreInfoRef.thenSideTable(TodoItem.class, String.class, "owners");
	}

	@ParameterizedTest
	@MethodSource("driverFactories")
	public void testTodoList(DriverFactory<TodoList> driverFactory) throws Exception {
		initialize(driverFactory);
		try (val context = bosk.readContext()) {
			assertEquals(listRef.value().items().size(), 0);
			TodoList list = listRef.value();
			TodoList newList = list.withItems(list.items().with(new TodoItem(item1ID, "Get groceries", Status.TODO, Listing.empty(items))));
			assertEquals(listRef.value().items().size(), 0);
			boskDriver.submitReplacement(listRef, newList);
			assertEquals(listRef.value().items().size(), 0);
		}
		try (val context = bosk.readContext()) {
			assertEquals(listRef.value().items().size(), 1);
			assertEquals(item1.value().status(), Status.TODO);
			setItem1Status(Status.DONE);
			assertEquals(item1.value().status(), Status.TODO);
		}
		try (val context = bosk.readContext()) {
			assertEquals(item1.value().status(), Status.DONE);
			setItem1Status(Status.CANCELED);
			assertEquals(item1.value().status(), Status.DONE);
			try (val innerContext = bosk.readContext()) {
				// Nested scope is the same as the outer scope
				assertEquals(item1.value().status(), Status.DONE);
			}
			ExecutorService executor = Executors.newFixedThreadPool(1);
			Future<?> future = executor.submit(()->{
				IllegalStateException caught = null;
				try {
					item1.value();
				} catch (IllegalStateException e) {
					caught = e;
				} catch (Throwable e) {
					fail("Unexpected exception: ", e);
				}
				assertNotNull(caught, "New thread should not have any scope by default, so an exception should be thrown");
				try (val unrelatedContext = bosk.readContext()) {
					assertEquals(item1.value().status(), Status.CANCELED);
				}
				try (val inheritedContext = context.adopt()) {
					assertEquals(item1.value().status(), Status.DONE); // Time travel!
					try (val reinheritedContext = inheritedContext.adopt()) {
						// Harmless to re-assert a scope you're already in
						assertEquals(item1.value().status(), Status.DONE);
					}
				}
			});
			future.get();
		}
		// Let's try adding an item
		Identifier item2ID = Identifier.from("item2");
		boskDriver.submitReplacement(bosk.reference(TodoItem.class, items.path().then(item2ID.toString())), new TodoItem(item2ID, "Item 2", Status.TODO, Listing.empty(items)));
		try (val context = bosk.readContext()) {
			TodoItem item2 = listRef.value().items().get(item2ID);
			assertEquals(item2ID, item2.id());

			// New item should go at the end
			assertEquals(Arrays.asList(item1ID, item2ID), listRef.value().items().ids());
		}
		// Delete one
		boskDriver.submitDeletion(items.then(item1ID));
		try (val context = bosk.readContext()) {
			assertEquals(Arrays.asList(item2ID), listRef.value().items().ids());
		}
		// Re-add it
		boskDriver.submitReplacement(item1, new TodoItem(item1ID, "Item 1", Status.TODO, Listing.empty(items)));
		try (val context = bosk.readContext()) {
			// New item should go at the end
			assertEquals(Arrays.asList(item2ID, item1ID), listRef.value().items().ids());
		}
		// Test buffering
		Reference<Status> item1status = bosk.reference(Status.class, item1.path().then("status"));
		boskDriver.submitReplacement(item1status, Status.TODO);
		BufferingDriver<TodoList> batch = BufferingDriver.writingTo(boskDriver);
		batch.submitReplacement(item1status, Status.DONE);
		try (val context = bosk.readContext()) {
			// Not applied yet
			assertEquals(Status.TODO, item1.value().status());
		}
		batch.flush();
		try (val context = bosk.readContext()) {
			assertEquals(Status.DONE, item1.value().status());
		}
		// Test Listings
		Reference<TodoItem> item2Ref = items.then(item2ID);
		ListingReference<TodoItem> item2Prerequisites = item2Ref.thenListing(TodoItem.class, "prerequisites");
		try (val context = bosk.readContext()) {
			assertTrue(item2Prerequisites.value().isEmpty());
			assertEquals(0, item2Prerequisites.value().size());
			boskDriver.submitReplacement(item2Prerequisites.then(item1ID), LISTING_ENTRY);
			assertTrue(item2Prerequisites.value().isEmpty());
		}
		try (val context = bosk.readContext()) {
			assertFalse(item2Prerequisites.value().isEmpty());
			assertEquals(1, item2Prerequisites.value().size());
			assertEquals(item1ID, item2Prerequisites.value().valueIterator().next().id());
			boskDriver.submitDeletion(item2Prerequisites.then(item1ID));
			assertEquals(item1ID, item2Prerequisites.value().valueIterator().next().id());
		}
		try (val context = bosk.readContext()) {
			assertTrue(item2Prerequisites.value().isEmpty());
		}
		// Test SideTables and Optional
		try (val context = bosk.readContext()) {
			// Try reaching through some nonexistent refs of various kinds
			assertEquals(null, moreInfoRef.valueIfExists());
			assertEquals(null, moreInfoRef.then(String.class, "remark").valueIfExists());
			assertEquals(null, moreInfoRef.thenSideTable(TodoItem.class, String.class, "owners").then(Identifier.from("nonexistent")).valueIfExists());
			assertEquals(null, items.then(Identifier.from("nonexistent")).then(String.class, "description").valueIfExists());
		}
		MoreInfo moreInfo = new MoreInfo("Original remark", SideTable.empty(items), ListValue.empty());
		boskDriver.submitReplacement(moreInfoRef, moreInfo);
		Reference<String> remarkRef = moreInfoRef.then(String.class, "remark");
		try (val context = bosk.readContext()) {
			assertEquals(moreInfo, moreInfoRef.valueIfExists());
			assertEquals("Original remark", remarkRef.value());
			boskDriver.submitReplacement(remarkRef, "New remark");
			assertEquals("Original remark", remarkRef.value());
		}
		try (val context = bosk.readContext()) {
			assertEquals("New remark", remarkRef.value());
		}
		boskDriver.submitReplacement(ownersRef.then(item1ID), "Ernie");
		try (val context = bosk.readContext()) {
			assertEquals("Ernie", ownersRef.then(item1ID).value());
			assertEquals(null, ownersRef.then(item2ID).valueIfExists());
			boskDriver.submitReplacement(ownersRef.then(item2ID), "Bert");
			assertEquals(null, ownersRef.then(item2ID).valueIfExists());
		}
		try (val context = bosk.readContext()) {
			assertEquals("Ernie", ownersRef.then(item1ID).value());
			assertEquals("Bert", ownersRef.then(item2ID).value());
			boskDriver.submitDeletion(ownersRef.then(item1ID));
			assertEquals("Ernie", ownersRef.then(item1ID).value());
			assertEquals("Bert", ownersRef.then(item2ID).value());
		}
		try (val context = bosk.readContext()) {
			assertEquals(null, ownersRef.then(item1ID).valueIfExists());
			assertEquals("Bert", ownersRef.then(item2ID).value());
		}
		boskDriver.submitDeletion(moreInfoRef);
		try (val context = bosk.readContext()) {
			assertEquals(null, moreInfoRef.valueIfExists());
			assertEquals(null, remarkRef.valueIfExists());
		}
		@SuppressWarnings("rawtypes")
		Reference<ListValue> externalTicketsRef = moreInfoRef.then(ListValue.class, "externalTickets");
		assertThrows(InvalidTypeException.class, ()->externalTicketsRef.then(int.class, "size"));
	}

	private void setItem1Status(Status newStatus) throws InvalidTypeException {
		bosk.driver().submitReplacement(bosk.reference(Status.class, item1.path().then("status")), newStatus);
	}

	@Accessors(fluent=true)
	@Getter
	@With
	@FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static class TodoList implements Entity {
		Identifier id;
		Catalog<TodoItem> items;
		Optional<MoreInfo> moreInfo;
	}

	@Accessors(fluent=true)
	@Getter
	@With
	@FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	public static class TodoItem implements Entity {
		Identifier id;
		String description;
		Status status;
		Listing<TodoItem> prerequisites;

		public enum Status { TODO, DONE, CANCELED }
	}

	@Accessors(fluent=true)
	@Getter
	@With
	@FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true)
	@RequiredArgsConstructor
	@EqualsAndHashCode
	@ToString
	public static class MoreInfo implements StateTreeNode {
		String remark;
		SideTable<TodoItem,String> owners;
		ListValue<String> externalTickets;
	}

}
