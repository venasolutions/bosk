![Release status](https://github.com/venasolutions/bosk/actions/workflows/release.yml/badge.svg)

# Bosk

![Three inquisitive cartoon trees with eyes](/art/bosk-3trees.png)

Bosk is a state management library for developing distributed application control-plane logic.
It's a bit like server-side Redux for Java, but without the boilerplate code.
(No selectors, no action objects, no reducers.)

Bosk eases the transition from a standalone application to a clustered high-availability replica set,
by supporting a programming style that minimizes the surprises encountered during the transition.
Bosk encourages reactive event-triggered closed-loop control logic
based on a user-defined immutable state tree structure,
and favours idempotency and determinism.

State is kept in memory, making reads extremely fast (on the order of 50ns).

Replication is achieved by activating an optional [MongoDB module](bosk-mongo), meaning the hard work of
change propagation, ordering, durability, consistency, atomicity, and observability,
as well as fault tolerance, and emergency manual state inspection and modification,
is all delegated to MongoDB: a well-known, reliable, battle-hardened codebase.
You don't need to trust Bosk to get all these details right:
all we do is send updates to MongoDB, and maintain the in-memory replica by following the MongoDB change stream.

## Getting Started

### Build settings

First, be sure you're compiling Java with the `-parameters` argument.

In Gradle:

```
dependencies {
	compileJava {
		options.compilerArgs << '-parameters'
	}

	compileTestJava {
		options.compilerArgs << '-parameters'
	}
}
```

In Maven:

```
<plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
                <compilerArgs>
                        <arg>-parameters</arg>
                </compilerArgs>
        </configuration>
</plugin>
```

### Standalone example

The [bosk-core](bosk-core) library is enough to create a `Bosk` object and start writing your application.

The library works particularly well with Java records.
You can define your state tree's root node as follows:

```
import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;

public record ExampleState (
	Identifier id,
	// Add fields here as you need them
	String name
) implements Entity {}
```

You can also use classes, especially if you're using Lombok:

```
@Value
@Accessors(fluent = true)
public class ExampleState implements Entity {
	Identifier id;
	String name;
}
```

Now declare your singleton `Bosk` class to house and manage your application state:

```
import io.vena.bosk.Bosk;
import io.vena.bosk.Identifier;
import io.vena.bosk.Path;
import io.vena.bosk.Reference;
import io.vena.bosk.exceptions.InvalidTypeException;

@Singleton // You can use your framework's dependency injection for this
public class ExampleBosk extends Bosk<ExampleState> {
	public ExampleBosk() throws InvalidTypeException {
		super(
			"ExampleBosk",
			ExampleState.class,
			new ExampleState(Identifier.from("example"), "world"),
			driverFactory());
	}

	// Typically, you add a bunch of useful references here, like this one:
	public final Reference<String> nameRef = reference(String.class, Path.parse("/name"));

	// Start off simple
	private static DriverFactory<ExampleState> driverFactory() {
		return Bosk::simpleDriver;
	}
}
```

You create an instance of `ExampleBosk` at initialization time,
typically using your application framework's dependency injection system.

To read state, acquire a `ReadContext`:

```
try (var __ = bosk.readContext()) {
	System.out.println("Hello, " + bosk.nameRef.value());
}
```

To modify state, use the `BoskDriver` interface:

```
bosk.driver().submitReplacement(bosk.nameRef, "everybody");
```

During your application's initialization, register a hook to perform an action whenever state changes:

```
bosk.registerHook("Name update", bosk.nameRef, ref -> {
	System.out.println("Name is now: " + ref.value());
});
```

After this, you can add in other packages as you need them,
like [bosk-gson](bosk-gson) for JSON serialization.
or [bosk-mongo](bosk-mongo) for persistence and replication.
Use the same version number for all packages.

### Replication

When you're ready to turn your standalone app into a replica set,
add [bosk-mongo](bosk-mongo) as a dependency
and change your Bosk `driverFactory` method to substitute `MongoDriver` in place of `Bosk::simpleDriver`:

```
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadConcern;
import com.mongodb.WriteConcern;
import io.vena.bosk.drivers.mongo.BsonPlugin;
import io.vena.bosk.drivers.mongo.MongoDriver;
import io.vena.bosk.drivers.mongo.MongoDriverSettings;

...

private static DriverFactory<ExampleState> driverFactory() {
	// Bosk requires certain client settings to provide the required consistency guarantees
	MongoClientSettings clientSettings = MongoClientSettings.builder()
		.readConcern(ReadConcern.MAJORITY)
		.writeConcern(WriteConcern.MAJORITY)
		.build();

	MongoDriverSettings driverSettings = MongoDriverSettings.builder()
		.database("ExampleBoskDB") // Bosks using the same name here will share state
		.build();

	// For advanced usage, you'll want to inject this object,
	// but for getting started, we can just create one here.
	BsonPlugin bsonPlugin = new BsonPlugin();

	return MongoDriver.factory(
		clientSettings,
		driverSettings,
		bsonPlugin);
}
```

To run this, you'll need a MongoDB replica set.
You can run a single-node replica set using the following `Dockerfile`:

```
FROM mongo:4.4
RUN echo "rs.initiate()" > /docker-entrypoint-initdb.d/rs-initiate.js 
CMD [ "mongod", "--replSet", "rsLonesome", "--port", "27017", "--bind_ip_all" ]
```

Now you can run multiple copies of your application, and they will share state.

## Development

### Code Structure

The repo is structured as a collection of subprojects because we publish several separate libraries.
[bosk-core](bosk-core) is the main functionality, and then other packages like [bosk-mongo](bosk-mongo) and [bosk-gson](bosk-mongo)
provide integrations with other technologies.

The subprojects are listed in [settings.gradle](settings.gradle), and each has its own `README.md` describing what it is.

### Compiler flags

Ensure `javac` is supplied the `-parameters` flag.

This is required because,
for each class you use to describe your Bosk state, the "system of record" for its structure is its constructor.
For example, you might define a class with a constructor like this:

```
public Member(Identifier id, String name) {...}
```

Based on this, Bosk now knows the names and types of all the "properties" of your object.
For this to work smoothly, the parameter names must be present in the compiled bytecode.

### Gradle setup

Each project has its own `build.gradle`.
Common settings across projects are in custom plugins under the [buildSrc directory](buildSrc/src/main/groovy).

### Versioning

In the long run, we'll use the usual semantic versioning.

For the 0.x.y releases, treat x as a manjor release number.

For the 0.0.x releases, all bets are off, and no backward compatibility is guaranteed.

### Logo

Logo was derived from this public-domain image: https://openclipart.org/detail/44023/small-trees-bushes

Modified by Grady Johnson.
