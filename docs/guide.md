## User's Guide

### The State Tree

Your application state takes the form of a tree of immutable node objects that you design.
The `Bosk` object keeps track of the root node, and the other nodes can be reached by traversing the tree from the root.

#### Paths and References
A node is identified by the sequence of steps required to reach that node from the root node.
The step sequence can be represented as a slash-delimited _path_ string;
for example, the path `"/a/b"` represents the node reached by calling `root.a().b()`.

##### Path objects

A `Path` object is the parsed form of a path string.
They can be created in three ways:
1. The `Path.parse` method accepts a slash-delimited path string. The segments of the path must be URL-encoded.
2. The `Path.of` method accepts a sequence of segment strings. The segments are not URL-encoded.
3. The `Path.then` method extends a path with additional trailing segments.

For example, the following three `Path` objects are identical:
- `Path.parse("/media/films/Star%20Wars")`
- `Path.of("media", "films", "Star Wars")`
- `Path.of("media", films").then("Star Wars")`

Path objects are always _interned_, meaning that two identical paths are always represented by the same `Path` object.

Paths are validated upon creation to make sure they are properly formatted;
otherwise, a `MalformedPathException` is thrown.

##### Reference objects

A `Reference` is a pointer to a node in the state tree, identified by its path.

`Reference` is one of the most important classes in Bosk. It is used in two primary ways:

1. A `Reference` can be created by the application (typically during initialization) to indicate a particular node to access, either to read its current value or to submit an update.
2. A `Reference` can be stored in the state tree itself to point to another node in the tree. (A `Reference` node cannot point to another `Reference` node.)

Unlike `Path`, `Reference` is type-checked upon creation to make sure it refers to an object that _could_ exist.
It is valid for a reference to refer to an object that _does not currently_ exist
(such as an `Optional.empty()` or a nonexistent `Catalog` entry),
but attempting to create a reference to an object that _cannot_ exist
(such as a nonexistent node field) results in an `InvalidTypeException`.

Two `Reference` objects are considered equal if they have the same path and the same root type.
In particular, references from two different bosks can be equal.

#### Node types

##### `StateTreeNode` and `Entity`

`StateTreeNode` is a marker interface you use to indicate that your class can be stored in a bosk.
It has no functionality.

A node's contents are defined by the names and types of its constructor's arguments.
Each argument must have a corresponding getter method with the same name, taking no arguments, and returning the same type.
(These conventions are compatible with `record` types, which are encouraged.)

`Entity` is a `StateTreeNode` that has a method `id()` returning an `Identifier`.
Certain objects in a bosk are required to be entities:
- The root object
- Any `Catalog` entry

##### `Catalog`

A `Catalog` is an immutable ordered set of `Entity` objects of a particular type.
A `Catalog` field establishes a one-to-many parent/child relationship between nodes.

Entities can be added or removed by calling the `with` and `without` methods, respectively.
The `with` operation is an "upsert" operation that replaces an entry if one already exists with a matching ID; otherwise, it adds the new entry to the end.
The `without` operation removes the entry with a given ID, leaving the remaining objects in the same order; if there is no such entry, the operation has no effect.

Because catalogs _contain_ their entries, the entries can be retrieved or iterated without a `ReadContext`.

##### `Listing`

A `Listing` is an ordered set of references to nodes in a particular `Catalog` referred to as the listing's _domain_.
A `Listing` establishes a one-to-many reference relationship between nodes.

A listing entry carries no information besides its existence.
A `Reference` to a listing entry is of type `Reference<ListingEntry>` and, if the entry exists,
it always has the value `LISTING_ENTRY`. (`ListingEntry` is a unit type.)

Just as a `Reference` points to a node that may or may not exist,
the entities pointed to by a `Listing` may or may not exist within the domain catalog;
that is, an entry can be added to a `Listing` even if the corresponding `Catalog` entry does not exist.

##### `SideTable`

A `SideTable` is an ordered map from nodes in a particular `Catalog`, referred to as the `SideTable`'s _domain_,
to some specified type of node.
A `SideTable` allows you to associate additional information with entities without adding fields to those entities.

##### `Phantom`

A `Phantom` field is a field that does not exist.
It behaves just like an `Optional` field that is always empty.

Phantom fields are primarily useful as the domain for a sparse `Listing` or `SideTable` in situations where there is no useful information to be stored about the key entities.
If you don't already know what this means, you probably don't want to use `Phantom`.

### The Bosk Object

The `Bosk` object is a container for your application state tree.
If it helps, you can picture it as an `AtomicReference<MyStateTreeRoot>` that your application can access,
though it actually does quite a lot more than this:
- it acts as a factory for `Reference` objects, which provide efficient access to specific nodes of your state tree,
- it provides stable thread-local state snapshots, via `ReadContext`,
- it provides a `BoskDriver` interface, through which you can modify the state tree, and
- it can execute _hook_ callback functions when part of the tree changes.

#### Initialization

Initialization of the `Bosk` object happens during its constructor.
Perhaps this seems self-evident, but it means a lot happens during the constructor, including running user-supplied code, in order to establish the initial bosk state invariants.

There are two primary things that need initializing:
- The `BoskDriver`
- The state tree

##### Driver initialization

First, the driver is initialized by calling the `DriverFactory` function passed in to the bosk constructor.
The driver factory is an important bosk extension point that allows functionality to be customized.
Every `Bosk` object has a _local driver_, which applies updates directly to the in-memory state tree;
the `DriverFactory` allows this to be extended with additional functionality by stacking "decorator" layers on top of the local driver.

The `DriverFactory` function is invoked in the `Bosk` constructor as follows:

```
this.driver = driverFactory.build(this, localDriver);
```

The return value of this function is stored, and becomes the object returned by `Bosk.driver()`.

Note that the driver accepts the `Bosk` object itself, even though this object is still under construction.
The reason for this is to allow drivers to create `Reference` objects, which requires the `Bosk` (which behaves as a `Reference` factory).
During the `DriverFactory`, the bosk object can be used for anything that doesn't involve accessing either the driver or the state tree, because neither of these is ready yet at the time the factory is called.
Other functionality, like creating references, or `Bosk.instanceID()`, works as expected.

##### State tree initialization

The state tree is initialized by calling `driver.initialState`.
Drivers are free to choose how the initial state is computed: they can supply the initial state themselves, or they can delegate to a downstream driver.
For example, `MongoDriver` will load the initial state from the database if it's available, and if not, it will delegate to the downstream driver.

If all of the drivers choose to delegate to their downstream drivers, ultimately the `initialState` method of the bosk's local driver will be called.
This method calls the `Bosk` constructor's `DefaultRootFunction` parameter to compute the initial state tree.
The overall effect of this setup is that the `DefaultRootFunction` parameter is only used if the bosk's driver does not supply the initial state.

### Reads

Bosk is designed to provide stable, deterministic, repeatable reads, using the `Reference` class.
`Reference` contains several related methods that provide access to the current state of the tree.

The most commonly used method is `Reference.value()`, which returns the current value of the reference's target node, or throws `NonexistentReferenceException` if the node does not exist.
A referenced node does not exist if any of the reference's path segments don't exist;
for example, a reference to `/planets/tatooine/cities/anchorhead` doesn't exist if there is no planet `tatooine`.

There are a variety of similar methods with slight variations in behaviour.
For example, `Reference.valueIfExists()` is like `valueIfExists`, but returns `null` if the node does not exist.

#### `ReadContext`

The core of bosk's approach to deterministic, repeatable behaviour is to avoid race conditions by using immutable data structures to represent program state.
To keep the state consistent over the course of an operation, bosk provides _snapshot-at-start_ behaviour:
the same state tree object is used throughout the operation, so that all reads are consistent with each other.

The `ReadContext` object defines the duration of a single "operation".
Without a `ReadContext`, a call to `Reference.value()` will throw `IllegalStateException`.
`ReadContext` is an `AutoCloseable` object that uses `ThreadLocal` to establish the state snapshot to be used for the duration of the operation:

```
try (var __ = bosk.readContext()) {
	exampleRef.value(); // Returns the value from the snapshot
}
exampleRef.value(); // Throws IllegalStateException
```

By convention, in the bosk library, methods that require an active read context have `value` in their name.

The intent is to create a read context at the start of an operation and hold it open for the duration so that the state is fixed and unchanging.
For example, if you're using a servlet container, use one read context for the entirety of a single HTTP endpoint method.
Creating many small read contexts opens your application up to race conditions due to state changes from one context to the next.

##### Creation

At any point in the code, a call to `bosk.readContext()` will establish a read context on the calling thread;
if there is already an active read context on the calling thread, the call to `readContext` will have no effect. 

##### Propagation

Sometimes a program will use multiple threads to perform a single operation, and it is wise to use the same state snapshot for all of them.
A snapshot from one thread can be used on another via `ReadContext.adopt`:

```
try (var __ = inheritedContext.adopt()) {
	exampleRef.value(); // Returns the same value as the thread that created inheritedContext
}
```

#### Parameters

A path can contain placeholders, called _parameters_, that can later be bound to `Identifier` values.
A reference whose path contains one or more parameters is referred to as a _parameterized reference_ (or sometimes an _indefinite_ reference).
Parameters are delimited by a hyphen character `-`:

```
Reference<City> anyCity = bosk.reference(City.class, Path.parseParameterized(
	"/planets/-planet-/cities/-city-"));
```

Parameter values can either be supplied by position or by value.
To supply parameters by position, use `Reference.boundTo`:

```
Reference<City> anchorhead = anyCity.boundTo(Identifier.from("tatooine"), Identifier.from("anchorhead"));
// Concrete reference to /planets/tatooine/cities/anchorhead
```

To supply parameters by name, generate a `BindingEnvironment` and use `Reference.boundBy`. For example, this produces the same concrete reference as the previous `boundTo` example:

```
BindingEnvironment env = BindingEnvironment.builder()
	.bind("planet", Identifier.from("tatooine"))
	.bind("city",   Identifier.from("anchorhead"))
	.build();
Reference<City> anchorhead = anyCity.boundBy(env);
```

You can also extract a binding environment using a parameterized reference to do pattern-matching:

```
BindingEnvironment env = anyCity.parametersFrom(anchorhead.path()); // binds -planet- and -city-
```

### Updates

The state tree is modified by submitting updates to the bosk's _driver_.

The `BoskDriver` interface accepts updates and causes them to be applied asynchronously to the bosk state.
Because updates are applied asynchronously, it's possible that intervening updates could cause the update to become impossible to apply;
for example, changing a field of an object that has been deleted.
Updates that can't be applied due to the contents of the bosk state are silently ignored.

In contrast, updates that are impossible to apply under any circumstances lead to exceptions thrown at submission time;
examples include an attempt to modify a nonexistent field in an existing object,
or an attempt to submit an update when an error has left the driver temporarily unable to accept updates.

#### Replacement

The most common form of update is `submitReplacement`, which supplies a new value for a node.
Replacement is an "upsert": the node is left in the desired state whether or not it existed before the update occurred.

An attempt to replace a component of a nonexistent object will be silently ignored;
for example, a replacement operation on `/planets/tatooine/cities` will be ignored if `tatooine` does not exist.[^nonexistent]

[^nonexistent]: It may seem preferable to throw an exception at submission time in such cases.
However, driver implementations are explicitly allowed to queue updates and apply them later,
since queueing is often a key strategy to achieve robust, scalable distributed systems.
Requiring synchronous confirmation about the current state of the bosk rules out queueing.
By requiring these operations to be ignored, bosk ensures the behaviour is the same in local development and in production,
and so any confusion caused by this behaviour should be encountered early on in the application development process.

#### Deletion

Some nodes in the tree can be deleted.
Examples include:
- Fields of type `Optional`
- Entries in a `Catalog`, `Listing` or `SideTable`

To delete such nodes, call `BoskDriver.submitDeletion`.
When applied, a deletion causes the node (and all its children) to become nonexistent.
The semantic nuances are similar to those of replacement.

#### Conditional updates

The replacement and deletion operations each have corresponding _conditional_ forms.
Conditional updates are silently ignored if a given `precondition` node does not have the specified `requiredValue` at the time the update is to be applied.
For example, `submitConditionalReplacement(target, newValue, precondition, requiredValue)` has the same effect as
`submitReplacement(target, newValue)`, unless the node referenced by `precondition` has a value other than `requiredValue` or does not exist.

`submitConditionalDeletion` is similar.

A third kind of conditional update, called `submitInitialization`, is like `submitReplacement` except ignored if the target node already exists.

#### `flush()`

The `flush()` method ensures all prior updates to the bosk have been applied,
meaning they will be reflected in a subsequent read context.

Formally, the definition of "prior updates" is the _happens-before_ relationship from the Java specification.
Conceptually, `flush` behaves as though it performs a "nonce" update to the bosk and then waits for that update to be applied;
the actual implementation may, of course, operate differently.
Even in parallel distributed setups with queueing, bosk updates are totally-ordered
(like _synchronizing operations_ from the Java spec),
so waiting for the "nonce" update ensures all prior updates have also been applied.

The semantics are such that the following example works correctly.
A bosk-based application is deployed as a replica set, with multiple servers sharing a single bosk (eg. using `MongoDriver`).
A client makes a request to the first server to update the bosk,
and then makes a request to the second server to call `flush()` and then read from the bosk.
In this scenario, the second request is guaranteed to reflect the update applied by the first request,
even though they are executed by different servers.

Calling `flush()` inside a read context will still apply the updates,
but those changes will not be reflected by any reads performed in the same read context,
since the read context continues using the state snapshot acquired when the read context began.

`Flush` does not guarantee that any hooks triggered by the applied updates will have been called yet.
To wait for a particular hook to run, the hook and application code must cooperate using a synchronization mechanism such as a semaphore.

### Hooks

The `Bosk.registerHook` method indicates that a particular call-back should occur any time a specified part of the state tree (the hook's _scope_) is updated.

Hooks are also called at registration time for all matching nodes.
They can also fire spontaneously; any application logic in a hook must be designed to accept additional calls even if the tree state didn't change.

A hook's scope can be a parameterized reference, in which case it will be called any time _any_ matching node is updated.

The hook call-back occurs inside a read context based on a state snapshot taken immediately after the triggering update occurred.

If a single update triggers multiple hooks, the hooks will run in the order they were registered.

#### Breadth-first ordering

It is fairly common for hooks to perform bosk updates, and these could themselves trigger additional hooks.
Triggered hooks are queued, and are run in the order they were queued.

For example, if one update triggers two hooks A and B, and then A performs an update that triggers hook C,
B will run before C. The hooks will reliably run in the order A, B, C.
When C runs, its read context will reflect the updates performed by A and C but not B, _even though B ran first_[^ordering].

[^ordering]: It might at first appear strange that hook C would not observe the effects of hook B, if B runs before C.
However, recall that, though B's updates will be _submitted_ before C runs, there is no guarantee that they will be _applied_ before C runs.
Suppose, for example, that we've chosen to deploy our application as a cluster that uses a queueing system
(perhaps for scalability, or change data capture, or any number of other reasons that distributed systems might use a queue).
This would cause a delay between when B _submits_ the update and when the bosk _applies_ the update.
Rather than expose users to a race condition in some operating environments that is not present in others,
bosk heavily favours consistency, and employs a convention that can be implemented efficiently in many environments:
updates from B are never visible in C's read scope.
Whatever confusion this might cause, that confusion will be encountered during initial application development, during initial tests,
rather than providing surprises when moving to a different environment for production.

See the `HooksTest` unit test for examples to illustrate the behaviour.

#### Exception handling

Any `Exception` thrown by a hook is caught, logged, and ignored.
This makes the hook execution loop robust against most bugs in hooks.

`Error`s are not ignored.
In particular, `AssertionError` is not ignored, which allows you to write unit tests that include assertions in hooks.

### Drivers

`BoskDriver` defines the interface by which updates are sent to a bosk.

The interface's update semantics are described in the _Updates_ section above.
This section focuses on the configuration and implementation of drivers, rather than their usage,
and briefly describes the drivers that are built into the bosk library.

#### Local driver

Every bosk has a _local driver_, which applies changes directly to the in-memory state tree.
If you use `Bosk::simpleDriver` as your driver factory when you initialize your `Bosk` object,
then the driver is _just_ the local driver.

The local driver is also the component responsible for triggering and executing hooks.

Despite the `BoskDriver` interface's asynchronous design, the local driver actually operates synchronously, and does not use a background thread.
The calling thread is used to trigger hooks, and even to run them (unless a hook is already running on another thread).

#### DriverStack and DriverFactory

`BoskDriver` itself is designed to permit stackable layers (the _Decorator_ design pattern),
making drivers modular and composable.

The simplest `DriverFactory` is `Bosk::simpleDriver`, which adds no driver layers at all, and simply returns the bosk's own local driver, which directly updates the Bosk's in-memory state tree.
More sophisticated driver layers can provide their own factories, which typically create an instance of the driver layer object configured to forward update requests to the downstream driver, forming a forwarding chain that ultimately ends with the bosk's local driver.

For example, an application could create a `LoggingDriver` class to perform logging of update requests before forwarding them to a downstream driver that actually applies them to the bosk state.

The `DriverFactory` interface is used to instantiate a driver layer, given the downstream driver object:

```
public interface DriverFactory<R extends Entity> {
	BoskDriver<R> build(Bosk<R> bosk, BoskDriver<R> downstream);
}
```

The `DriverStack` class facilitates the composition of driver layers.
For example, a stack could be composed as follows:

```
DriverFactory<ExampleState> mongoDriverFactory() {
	return DriverStack.of(
		LoggingDriver.factory("Submitted to MongoDriver"),
		MongoDriver.factory(...)
	);
}
```

This creates a "logging sandwich", configured to process each update as follows:
1. The `LoggingDriver` will log the event, and forward it to the `MongoDriver`
2. The `MongoDriver` will send the update to MongoDB, and then receive a change event and forward it to the bosk's local driver
3. The local driver will update the in-memory state tree

Later on, this could even be extended by sandwiching the `MongoDriver` between two `LoggingDriver` instances,
in order to log events submitted to and received from `MongoDriver`:

```
DriverFactory<ExampleState> mongoDriverFactory() {
	return DriverStack.of(
		LoggingDriver.factory("Submitted to MongoDriver"),
		MongoDriver.factory(...),
		LoggingDriver.factory("Received from MongoDriver") // NEW LAYER!
	);
}
```

The `DriverFactory` and `DriverStack` classes make this a one-line change.

Note that `DriverStack` extends `DriverFactory`; that is, a `DriverStack` is a kind of `DriverFactory` that invokes other factories to assemble a driver.
The local driver is not explicitly named in the driver stack.

#### Built-in drivers

Some handy drivers ship with the `bosk-core` module.
This can be useful in composing your own drivers, and in unit tests.

- `BufferingDriver` queues all updates, and applies them only when `flush()` is called.
- `ForwardingDriver` accepts a collection of zero or more downstream drivers, and forwards all updates to all of them
- `MirroringDriver` accepts updates to one bosk, and emits corresponding updates to another similar bosk

#### `MongoDriver` and `bosk-mongo`

`MongoDriver` is an important enough driver that it deserves its own section.
By using `MongoDriver` (by adding the `bosk-mongo` dependency to your project),
you can turn your server into a replica set with relatively little difficulty.

`MongoDriver` uses MongoDB as a broadcast medium to deliver bosk updates to all the servers in your replica set.
Newly booted servers connect to the database, initialize their bosk from the current database contents, and follow the MongoDB change stream to receive updates.

##### Configuration and usage

Like most drivers, `MongoDriver` is not instantiated directly, but instead provides a `DriverFactory` to simplify composition with other driver components.
Create a `MongoDriverFactory` by calling `MongoDriver.factory`:

```
static <RR extends Entity> MongoDriverFactory<RR> factory(
	MongoClientSettings clientSettings,
	MongoDriverSettings driverSettings,
	BsonPlugin bsonPlugin
) { ... }
```

The arguments are as follows:

- `clientSettings` is how the MongoDB client library configures the database connection. Bosk has consistency requirements that make certain settings mandatory: specifically, the `readConcern` and `writeConcern` must both be set to `MAJORITY`.
- `driverSettings` contains the bosk-specific settings, the most important of which is `database` (the name of the database in which the bosk state is to be stored). Bosks that use the same database will share the same state.
- `bsonPlugin` controls the translation between BSON objects and the application's state tree node objects. For simple scenarios, the application won't need to worry about this object, and can simply instantiate one and pass it in.

Here is an example of a method that would return a fully configured `MongoDriverFactory`:

```
static DriverFactory<ExampleState> driverFactory() {
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

##### Database setup

Bosk supports MongoDB 4.4 and up.

To support change streams, MongoDB must be deployed as a replica set.
In production, this is a good practice anyway, so this requirement shouldn't cause any hardship:
the MongoDB documentation recommends against deploying a standalone server to production.

For local development, standalone MongoDB servers don't support change streams (for some reason).
To support `MongoDriver`, you must use a replica set, even if you are running just one server.
This can be achieved using the following `Dockerfile`:

```
FROM mongo:4.4 # ...but use a newer version if you can
RUN echo "rs.initiate()" > /docker-entrypoint-initdb.d/rs-initiate.js 
CMD [ "mongod", "--replSet", "rsLonesome", "--port", "27017", "--bind_ip_all" ]
```

##### Robustness and Serviceability

An important design principle of `MongoDriver` is that it should be able to recover from temporary outages without requiring an application reboot.
When faced with a situation it can't cope with, `MongoDriver` has just one fallback mode of operation: a _disconnected_ state that does not process changes from the database.
Once disconnected, `MongoDriver` will no longer send updates downstream, and so the in-memory state will stay frozen until bosk can reconnect.

Recovering from a disconnected state occurs automatically when conditions improve, and should not require any explicit action to be taken.
Also, no particular sequence of steps should be required to recover: any actions that an operator then takes to restore the database state and connectivity should have the expected effect.

For example, suppose the bosk database were to be deleted.
`MongoDriver` would respond by suspending updates, and leaving the last known good state intact in memory.
Perhaps the operator takes the database offline entirely, then reboots it and restores the last known good state from a backup.
`MongoDriver` would respond by reconnecting to the database (possibly after some period of time) and reloading the database state to re-sync the in-memory state with the database.

As a special exception to this philosophy, `MongoDriver` will throw a fatal exception if it cannot connect to MongoDB during startup.
The rationale is that such connectivity errors are far more likely to result from a misconfiguration than from a temporary database outage,
making it counterproductive for the driver to behave as though initialization was successful.

##### Database format & layout

The current database layout stores the entire bosk state in a single document in a single collection.
The collection is called `boskCollection` and the document has four fields:

- `_id`: this is always `boskDocument`
- `path`: this is always `/`
- `state`: contains the entire bosk state tree
- `echo`: contains a nonce value; used to implement `flush()`

The format of the `state` field is determined by `BsonPlugin`.
The code for `BsonPlugin` will have the details, but some high-level points about the BSON format:

- It does not match the JSON format generated by `GsonPlugin`. The two are not mutually compatible. This is a deliberate decision based on differing requirements.
- It strongly favours objects over arrays, because object members offer idempotency and (ironically) stronger ordering guarantees.

##### Schema upgrades: how to add a new field

In general, bosk does not support `null` field values.
This means if you add a new field to your state tree node classes, they become incompatible with the existing database contents (which do not have that field).

This means that new fields must, at least initially, be declared as `Optional`.
If your application code is ok with the field being `Optional`, and can cope with that field's absence, you can stop here.
Otherwise, you must add your field in two steps.

In the first step, you declare the field to be `Optional` and supply a default value to make it behave as though the field were present in the database.
Declare the constructor argument to be `Optional`, and supply the default value as follows:

```
ExampleNode(Optional<ExampleValue> newField) {
	if (newField.isPresent()) {
		this.newField = newField;
	} else {
		this.newField = Optional.of(ExampleValue.DEFAULT_VALUE);
	}
}
```

This way, any updates written to MongoDB will include the new field, so the state will be gradually upgraded to include the new field.
Because MongoDriver ignores any fields in the database it doesn't recognize,
this new version of the code can coexist with older versions that don't know about this field.

The next step is to ensure that any older versions of the server are shut down.
This will prevent _new_ objects from being created without the new field.

Next, call `MongoDriver.refurbish()`.
This method rewrites the entire bosk state in the new format, which has the effect of adding the new field to all existing objects.

Finally, you can make your new field non-optional.
For example, you can change it from `Optional<ExampleValue>` to just `ExampleValue`,
save in the knowledge that there are no objects in the database that don't have this field.

#### Compliance rules

`BoskDriver` implementations typically take the form of a stackable layer that accepts update requests, performs some sort of processing, and forwards the (possibly modified) requests to the next driver in the chain (the _downstream_) driver.
This is a powerful technique to add functionality to a Bosk instance.

To retain compatibility with application code, however, driver implementations must obey the `BoskDriver` contract.
The low-level details of that contract are well documented in the `BoskDriver` javadocs, and are tested in the `DriverConformanceTest` class,
but there are also important higher-level rules governing the allowed differences between the updates a driver receives and those it forwards to the downstream driver.
Breaking these rules might alter application behaviour in ways that the developers won't be expecting.

Broadly, the validity of a sequence of updates can be understood in terms of the implied _sequence of states_ that exist between updates.
The rules are:
- A state can be skipped
- A state can be repeated any number of times in a row
- No swapping the order of states
- No completely novel states that weren't in the original update stream

Some specific changes are allowed. A driver may:
1. omit a change that has no effect on the state
2. substitute a smaller change having the same effect
3. re-apply a prior update if that has no effect
4. omit any contiguous sequence of updates having no overall effect
5. combine a contiguous sequence of updates into a single update with the same overall effect

These rules require that the driver maintains an awareness of the current bosk state, which _most drivers do not_,
and so most drivers are rarely able to take advantage of these options,
because they can't generally determine what effect an update will have.

### Serialization: `bosk-gson`

The `bosk-gson` module uses the Gson library to support JSON serialization and deserialization.

#### Configuring the Gson object

To configure a `Gson` object that is compatible with a particular `Bosk` object, use the `GsonPlugin.adaptersFor` method.
Here is an example:

```
GsonPlugin gsonPlugin = new GsonPlugin();
boskGson = new GsonBuilder()
	.registerTypeAdapterFactory(gsonPlugin.adaptersFor(bosk))

	// You can add whatever configuration suits your application.
	// We recommend these.
	.excludeFieldsWithoutExposeAnnotation()
	.setPrettyPrinting()

	.create();
```

Note that `GsonPlugin` is compatible with many of the Gson configuration options, so you should be able to configure it as you want.

#### Format

Most nodes are serialized in the expected fashion, with one member per field,
and child objects nested inside parents.

The format of the various built-in types is shown below.

```
"reference": "/a/b/c", // References are strings
"catalog": [           // Catalogs are arrays of single-member objects
	{
		"entry1": {
			"id": "entry1", // The id field is included here (redundantly)
			...
		}
	}
],
"listing": {           // Listings are objects with two fields
	"ids": ["entry1", "entry2"],
	"domain": "/catalog"   // Reference to the containing Catalog
},
"sideTable": {         // SideTables are objects with two fields
	"valuesById": [
		{ "entry1": { ... } },
		{ "entry2": { ... } }
	],
	"domain": "/catalog"   // Reference to the containing Catalog
}
```

A field of type `Optional<T>` is simply serialized as a `T`, unless the optional is empty, in which case the field does not appear at all.

A field of type `Phantom<T>` is not serialized (just like `Optional.empty()`).

Fields marked as `@Self` or `@Enclosing` are not serialized.
They are inferred automatically at deserialization time.

#### DeserializationScope

In order to infer the correct values of `@Self` and `@Enclosing` references,
the deserialization process must keep track of the current location in the state tree.
This is simple when deserializing the entire bosk state: the location starts in the root object,
and from there, the format is designed in such a way that the location can be tracked as JSON parsing proceeds.

However, when deserializing only part of the bosk state (which is by far the most common situation),
the deserialization must know the corresponding state tree location so it can compute `@Self` and `@Enclosing` references.

To deserialize just one node of the bosk state, use a try-with-resources statement to wrap the deserialization in a `DeserializationScope` object initialized with the path of the node being deserialized:

```
try (var __ = gsonPlugin.newDeserializationScope(ref)) {
	newValue = gson.fromJson(exampleJson, ref.targetType());
}
```

#### DerivedRecord

In inner-loop, high-performance code, it can be too costly to use `Reference.value()` to access node objects, and it is definitely too costly to create new `Reference` objects.
In those cases, it may be preferable to use the node objects directly.
If you construct an object containing some node objects directly, and you then want to serialize that object as though they were `Reference`s instead,
you can annotate the class with `@DerivedRecord`.
All the "directly-contained node" objects must implement `ReflectiveEntity`.
The serialization process will call `ReflectiveEntity.reference()` to compute the reference, which will be serialized as a string.

### Recommendations

#### Create a subclass of `Bosk` and create references at startup

References are designed to be created once and reused many times.
Occasionally, you can create references dynamically, but it will be slower, and usually there's no need.

A typical pattern is to create a `Bosk` subclass containing a long list of references your application needs.
Larger apps might want to break up this list and put references into separate classes,
but small apps can dump them all into the `Bosk` object itself.

As a naming convention,
- Definite references (with no parameters) end with `Ref`
- Indefinite references (with parameters) start with `any` and do not end with `Ref`

Example:

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
	public final Reference<String> nameRef = reference(String.class, Path.parse(
		"/name"));

	public final Reference<ExampleWidget> anyWidget = reference(ExampleWidget.class, Path.parseParameterized(
		"/widgets/-widget-"));

	// Start off simple
	private static DriverFactory<ExampleState> driverFactory() {
		return Bosk::simpleDriver;
	}
}
```

#### Services, tenants, catalogs

To reduce coupling between different parts of a large codebase sharing a single bosk,
the fields of the root node are typically different "services" owned by different development teams.
The next level would be a `Catalog` of tenants or users, depending on your application's tenancy pattern.
Finally, within a tenant node, many of the important objects are stored in top-level catalogs,
rather than existing only deeper in the tree.

For example, a typical bosk path might look like `/exampleService/tenants/-tenant-/exampleWidgets/-widget-`.

#### Arrange state by who modifies it

There is a tendency to place all state relevant to some object _inside that object_.
Bosk encourages you to separate state that is modified by different parts of the code,
employing `SideTable`s rather than putting all state in the same object.

For example, suppose your application distributes shards of data to worker nodes in a cluster.
You could imagine a `Worker` object like this:

```
public record Worker (
	Identifier id,
	String baseURL,
	Catalog<Shard> assignedShards,
	Status status
) {}
```

**Don't do this**. The trouble is, this puts state into the same object that is changed under three different circumstances:
- `baseURL` is set by static configuration or by service discovery. This is _configuration_.
- `assignedShards` is set by the data distribution algorithm. This is a _decision_.
- `status` is set either by a polling mechanism, or when worker communications result in an error. This is an _observation_.

You want to separate configuration from decisions from observations.
The entity itself should contain only configuration; decisions and observations should be stored in `SideTable`s.

A better arrangement of this state might look like this:

```
public record Cluster (
	Catalog<Worker> workers,
	SideTable<Worker, Shard> workerAssignments,
	SideTable<Worker, Status> workerStatus
) {}

public record Worker (
	Identifier id,
	String baseURL
) {}
```

#### Use large read contexts

Using more than one `ReadContext` for the same operation causes that operation to be exposed to race conditions from concurrent state updates.

For any one operation, use a single `ReadContext` around the whole operation.
The "operation" should be as coarse-grained as feasible.

Some examples:
- An HTTP endpoint method should be enclosed in a single `ReadContext`. Typically this is done by installing a servlet filter that acquires a `ReadContext` around any `GET`, `HEAD`, or `POST` request (assuming you use `POST` as "`GET` with a body". If you use RPC-style `POST` endpoints, you might not be able to have a single `ReadContext` around the entire endpoint.) Note that `PUT` and `DELETE` typically don't need a `ReadContext` at all.
- A scheduled action (eg. using the `@Scheduled` annotation in Spring Boot) should immediately acquire a `ReadContext` for its entire duration

In general, open one large `ReadContext` as early as possible in your application's call stack unless this is unworkable for some reason.

#### Closed-loop control hooks

Bosk is often used to control a server's _local state_.
For example, a caching application could use bosk to control what's in the cache,
so that all servers have the same cache contents and therefore provide reliable response times across the cluster.
This is _local state_ because the state exists independently in each server instance.

To make your system declarative and idempotent,
write your hooks in a style that follows these steps:

1. From the current bosk state, compute the desired local state
2. Compare the desired state with the actual local state
3. If they differ, make changes to the local state to make it match the desired state

This style leads to more stable systems than imperative-style hooks that respond to bosk updates by issuing arbitrary imperative commands.

### Glossary

_Apply_: When an update has been _applied_ to the bosk, it will be reflected in a subsequent read context

_Driver_: An object that accepts and processes bosk updates

_Entity_: A state tree node with an `id` field, which can participate in certain bosk features.
Catalog entries must be entities, for example.

_Node_: An object in the state tree.

_Path_: The sequence of fields that reaches a particular state tree node starting from the tree's root node.

_Parameter_: A path segment that can be substituted for an `Identifier`.

_Reference_: A type-safe representation of a `Path` that can be used to access a node in a particular `Bosk` object.

_Root_: The topmost, or outermost, state object in a bosk.

_Scope_: (of a hook) a reference to the node (or nodes, if the scope is parameterized) being watched for changes. Any updates to that node will cause the hook to be triggered.

_Segment_: A portion of a path between slashes. The path `/a/b/c` has three segments: `a`, `b`, and `c`. In its string representation, the segments of a path are URL-encoded.

_Submit_: (of an update) to be sent to the driver for subsequent execution.

_Trigger_: (of a hook) to be queued for execution. A hook is triggered whenever its scope node is updated. The execution may happen immediately, or it may happen later, depending on the circumstances.