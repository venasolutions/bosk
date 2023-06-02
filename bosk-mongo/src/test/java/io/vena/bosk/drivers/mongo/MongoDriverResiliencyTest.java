package io.vena.bosk.drivers.mongo;

import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.Listing;
import io.vena.bosk.drivers.state.TestEntity;
import io.vena.bosk.exceptions.FlushFailureException;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.junit.ParametersByName;
import java.io.IOException;
import java.util.stream.Stream;
import lombok.var;
import org.junit.jupiter.api.Disabled;

import static io.vena.bosk.ListingEntry.LISTING_ENTRY;
import static io.vena.bosk.drivers.mongo.MongoDriverSettings.ImplementationKind.RESILIENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A set of tests that only work with {@link io.vena.bosk.drivers.mongo.MongoDriverSettings.ImplementationKind#RESILIENT}
 */
public class MongoDriverResiliencyTest extends AbstractMongoDriverTest {

	@ParametersByName
	public MongoDriverResiliencyTest(MongoDriverSettings.MongoDriverSettingsBuilder driverSettings) {
		super(driverSettings);
	}

	@SuppressWarnings("unused")
	static Stream<MongoDriverSettings.MongoDriverSettingsBuilder> driverSettings() {
		return Stream.of(
			MongoDriverSettings.builder()
				.database("boskTestDB_resiliency")
				.implementationKind(RESILIENT)
		);
	}

	@ParametersByName
	@DisruptsMongoService
	@Disabled("Only supported for ImplementationKind = RESILIENT")
	void initialOutage_boskRecovers() throws InvalidTypeException, InterruptedException, IOException {
		// Set up the database contents to be different from initialRoot
		Bosk<TestEntity> prepBosk = new Bosk<TestEntity>(
			"Prep bosk",
			TestEntity.class,
			bosk -> initialRoot(bosk).withString("distinctive string"),
			driverFactory);
		prepBosk.driver().flush();
		((MongoDriver<TestEntity>)prepBosk.driver()).close();

		TestEntity defaultState = initialRoot(prepBosk);
		TestEntity mongoState = defaultState.withString("distinctive string");

		mongoService.proxy().setConnectionCut(true);

		Bosk<TestEntity> bosk = new Bosk<TestEntity>("Test bosk", TestEntity.class, this::initialRoot, driverFactory);
		MongoDriverSpecialTest.Refs refs = bosk.buildReferences(MongoDriverSpecialTest.Refs.class);
		BoskDriver<TestEntity> driver = bosk.driver();

		try (var __ = bosk.readContext()) {
			assertEquals(defaultState, bosk.rootReference().value(),
				"Uses default state if database is unavailable");
		}

		assertThrows(FlushFailureException.class, driver::flush,
			"Flush disallowed during outage");
		assertThrows(Exception.class, () -> driver.submitReplacement(bosk.rootReference(), initialRoot(bosk)),
			"Updates disallowed during outage");

		mongoService.proxy().setConnectionCut(false);

		driver.flush();
		try (var __ = bosk.readContext()) {
			assertEquals(mongoState, bosk.rootReference().value(),
				"Updates to database state once it reconnects");
		}

		// Make a change to the bosk and verify that it gets through
		driver.submitReplacement(refs.listingEntry(entity123), LISTING_ENTRY);
		TestEntity expected = initialRoot(bosk)
			.withString("distinctive string")
			.withListing(Listing.of(refs.catalog(), entity123));


		driver.flush();
		try (@SuppressWarnings("unused") Bosk<?>.ReadContext readContext = bosk.readContext()) {
			assertEquals(expected, bosk.rootReference().value());
		}
	}

}
