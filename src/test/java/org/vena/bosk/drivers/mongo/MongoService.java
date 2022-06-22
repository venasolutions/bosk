package org.vena.bosk.drivers.mongo;

import com.mongodb.MongoClientSettings;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.io.Closeable;
import org.jetbrains.annotations.NotNull;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import static com.mongodb.ReadPreference.secondaryPreferred;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * An interface to the dockerized MongoDB replica set,
 * suitable for use by a test class.
 *
 * <p>
 * Use {@link UsesMongoService} and {@link DisruptsMongoService} to make sure
 * that the network outage tests don't run at the same time as
 * other tests that need MongoDB.
 *
 * <p>
 * All instances use the same MongoDB container, so
 * different tests should use different database names.
 * All instances use the same proxy, so network outage tests
 * will disrupt other tests running in parallel.
 *
 */
public class MongoService implements Closeable {
	// Expensive stuff shared among instances as much as possible
	private static final Network NETWORK = Network.newNetwork();
	private static final GenericContainer<?> MONGO_CONTAINER = mongoContainer();
	private static final ToxiproxyContainer TOXIPROXY_CONTAINER = toxiproxyContainer();
	private static final ToxiproxyContainer.ContainerProxy proxy = TOXIPROXY_CONTAINER.getProxy(MONGO_CONTAINER, 27017);
	private static final MongoClientSettings clientSettings = mongoClientSettings(new ServerAddress(proxy.getContainerIpAddress(), proxy.getProxyPort()));
	private static final MongoClient mongoClient = MongoClients.create(clientSettings);

	public ToxiproxyContainer.ContainerProxy proxy() {
		return proxy;
	}

	public MongoClientSettings clientSettings() {
		return clientSettings;
	}

	public MongoClient client() {
		return mongoClient;
	}

	@Override
	public void close() {
		mongoClient.close();
	}

	private static GenericContainer<?> mongoContainer() {
		GenericContainer<?> result = new GenericContainer<>(
			new ImageFromDockerfile().withDockerfileFromBuilder(builder -> builder
				.from("mongo:4.0")
				.run("echo \"rs.initiate()\" > /docker-entrypoint-initdb.d/rs-initiate.js")
				.cmd("mongod", "--replSet", "rsLonesome", "--port", "27017", "--bind_ip_all")
				.build()))
			.withNetwork(NETWORK)
			.withExposedPorts(27017);
		result.start();
		return result;
	}

	private static ToxiproxyContainer toxiproxyContainer() {
		ToxiproxyContainer result = new ToxiproxyContainer(
			DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.2.0").asCompatibleSubstituteFor("shopify/toxiproxy"))
			.withNetwork(NETWORK);
		result.start();
		return result;
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
