package io.vena.bosk.drivers.mongo;

import io.vena.bosk.drivers.HanoiTest;
import io.vena.bosk.drivers.mongo.TestParameters.ParameterSet;
import io.vena.bosk.junit.ParametersByName;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;

import static io.vena.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;
import static io.vena.bosk.drivers.mongo.TestParameters.EventTiming.NORMAL;

public class MongoDriverHanoiTest extends HanoiTest {
	private static MongoService mongoService;

	@ParametersByName
	public MongoDriverHanoiTest(ParameterSet parameters) {
		MongoDriverSettings settings = parameters.driverSettingsBuilder().build();
		this.driverFactory = MongoDriver.factory(
			mongoService.clientSettings(),
			settings,
			new BsonPlugin()
		);
		mongoService.client()
			.getDatabase(settings.database())
			.drop();
	}

	@BeforeAll
	static void setupMongoConnection() {
		mongoService = new MongoService();
	}

	@SuppressWarnings("unused")
	static Stream<ParameterSet> parameters() {
		return TestParameters.driverSettings(
			Stream.of(
				PandoFormat.oneBigDocument(),
				PandoFormat.withGraftPoints("/puzzles"),
				PandoFormat.withGraftPoints("/puzzles/-puzzle-/towers"),
				PandoFormat.withGraftPoints("/puzzles", "/puzzles/-puzzle-/towers/-tower-/discs"),
				SEQUOIA
			),
			Stream.of(NORMAL)
		);
	}

}
