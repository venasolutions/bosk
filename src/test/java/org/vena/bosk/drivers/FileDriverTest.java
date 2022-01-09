
package org.vena.bosk.drivers;

import com.google.gson.Gson;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vena.bosk.AbstractBoskTest;
import org.vena.bosk.Bosk;
import org.vena.bosk.Identifier;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileDriverTest extends AbstractBoskTest {
	File tempFile;

	@BeforeEach
	void createTempFile() throws IOException {
		tempFile = File.createTempFile(FileDriverTest.class.getSimpleName(), ".json");
	}

	@AfterEach
	void deleteTempFile() {
		boolean deleted = tempFile.delete();
		assertTrue(deleted);
	}

	@Test
	void fileCreatedAfterInit() throws Exception {
		boolean deleted = tempFile.delete(); // now it's nonexistent
		assertTrue(deleted);

		Bosk<TestRoot> bosk = setUpBoskWithFileDriver();

		TestRoot initialValue = initialRoot(bosk);
		assertEquals(initialValue, currentRoot(bosk), "With no file, default to the downstream driver's initial root");

		TestRoot newValue = initialValue.withSomeStrings(new StringListValueSubclass("newly", "added", "strings"));
		assertNotEquals(initialValue, newValue, "Must be able to tell the objects apart for the test to be meaningful");
		writeToFile(newValue, gsonFor(bosk));

		// Note that, while technically required according to the BoskDriver spec, the FileDriver
		// doesn't actually need this, and we'd be better off without it because it can cause the
		// test to pass even if the FileDriver is not responding at all to changes in the file;
		// in fact, as I write this, that is what is currently happening! -pdoyle
		// TODO: Debug the weird WatchService code
		bosk.driver().flush();

		assertEquals(newValue, currentRoot(bosk), "Bosk should be updated");
	}

	@Test
	void initialRoot_fromFile() throws Exception {
		// Initialize file
		Bosk<TestRoot> disposableBosk = setUpBosk(Bosk::simpleDriver);
		TestRoot standardInitialRoot = initialRoot(disposableBosk);
		StringListValueSubclass distinctiveStrings = new StringListValueSubclass(tempFile.getName());
		TestRoot initialValue = standardInitialRoot.withSomeStrings(distinctiveStrings);
		assertNotEquals(standardInitialRoot, initialValue, "Root values must be distinct or tests will get false passes");
		writeToFile(initialValue, gsonFor(disposableBosk));

		Bosk<TestRoot> bosk = setUpBoskWithFileDriver();
		TestRoot expected = initialRoot(bosk).withSomeStrings(distinctiveStrings);
		assertEquals(expected, currentRoot(bosk), "Bosk should initialize from file");
	}

	@Test
	void initialRoot_blankFile() {
		Bosk<TestRoot> bosk = setUpBoskWithFileDriver();
		TestRoot expected = initialRoot(bosk);
		assertEquals(expected, currentRoot(bosk), "FileDriver should defer to downstream for blank file");
	}

	@Test
	void initialRoot_invalidContents() throws Exception {
		try (BufferedWriter writer = Files.newBufferedWriter(tempFile.toPath(), CREATE, WRITE)) {
			writer.write("This is not JSON\n");
		}

		Bosk<TestRoot> bosk = setUpBoskWithFileDriver();
		TestRoot expected = initialRoot(bosk);
		assertEquals(expected, currentRoot(bosk), "FileDriver should defer to downstream for blank file");
	}

	@Test
	void submitInitialization() {
		Bosk<TestRoot> bosk = setUpBoskWithFileDriver();
		assertThrows(IllegalArgumentException.class, () -> bosk.driver().submitInitialization(bosk.rootReference(), initialRoot(bosk)));
	}

	@Test
	void submitReplacement() {
		Bosk<TestRoot> bosk = setUpBoskWithFileDriver();
		assertThrows(IllegalArgumentException.class, () -> bosk.driver().submitReplacement(bosk.rootReference(), initialRoot(bosk)));
	}

	@Test
	void submitDeletion() {
		Bosk<TestRoot> bosk = setUpBoskWithFileDriver();
		assertThrows(IllegalArgumentException.class, () -> bosk.driver().submitDeletion(entityReference(Identifier.from("123"), bosk)));
	}

	@Test
	void flush() throws Exception {
		Bosk<TestRoot> bosk = setUpBoskWithBufferingFileDriver();
		Gson gson = gsonFor(bosk);
		TestRoot value1 = currentRoot(bosk);
		TestRoot value2 = value1.withSomeStrings(new StringListValueSubclass("value2"));

		writeToFile(value2, gson);
		assertEquals(value1, currentRoot(bosk), "value2 is still buffered");
		bosk.driver().flush();
		assertEquals(value2, currentRoot(bosk), "value2 has arrived");
	}

	private TestRoot currentRoot(Bosk<TestRoot> bosk) {
		try (@SuppressWarnings("unused") Bosk<?>.ReadContext context = bosk.readContext()) {
			return bosk.rootReference().value();
		}
	}

	private Bosk<TestRoot> setUpBoskWithFileDriver() {
		return setUpBosk((downstream, bosk) -> new FileDriver<>(
				tempFile.toPath(),
				gsonFor(bosk),
				bosk.rootReference(),
				downstream));
	}

	private Bosk<TestRoot> setUpBoskWithBufferingFileDriver() {
		return setUpBosk((downstream, bosk) -> new FileDriver<>(
				tempFile.toPath(),
				gsonFor(bosk),
				bosk.rootReference(),
				BufferingDriver.writingTo(downstream)));
	}

	private void writeToFile(TestRoot newValue, Gson gson) throws IOException {
		try (BufferedWriter writer = Files.newBufferedWriter(tempFile.toPath(), CREATE, WRITE)) {
			gson.toJson(newValue, TestRoot.class, writer);
		}
	}

}
