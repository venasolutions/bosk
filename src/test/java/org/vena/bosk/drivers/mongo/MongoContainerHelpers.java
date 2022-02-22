package org.vena.bosk.drivers.mongo;

import com.mongodb.MongoClientSettings;
import com.mongodb.ServerAddress;
import org.jetbrains.annotations.NotNull;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import static com.mongodb.ReadPreference.secondaryPreferred;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class MongoContainerHelpers {
	static GenericContainer<?> mongoContainer(Network network) {
		return new GenericContainer<>(
			new ImageFromDockerfile().withDockerfileFromBuilder(builder -> builder
				.from("mongo:4.0")
				.run("echo \"rs.initiate()\" > /docker-entrypoint-initdb.d/rs-initiate.js")
				.cmd("mongod", "--replSet", "rsLonesome", "--port", "27017", "--bind_ip_all")
				.build()))
			.withNetwork(network)
			.withExposedPorts(27017);
	}

	static ToxiproxyContainer toxiproxyContainer(Network network) {
		return new ToxiproxyContainer(
			DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.2.0").asCompatibleSubstituteFor("shopify/toxiproxy"))
			.withNetwork(network);
	}

	@NotNull
	static MongoClientSettings mongoClientSettings(ServerAddress serverAddress) {
		int initialTimeoutMS = 60_000;
		int queryTimeoutMS = 5_000; // Don't wait an inordinately long time for network outage testing
		return MongoClientSettings.builder()
			.readPreference(secondaryPreferred())
			.applyToClusterSettings(builder -> {
				builder.hosts(singletonList(serverAddress));
				builder.serverSelectionTimeout(initialTimeoutMS, MILLISECONDS);
			})
			.applyToSocketSettings(builder -> {
				builder.connectTimeout(initialTimeoutMS, MILLISECONDS);
				builder.readTimeout(queryTimeoutMS, MILLISECONDS);
			})
			.build();
	}
}
