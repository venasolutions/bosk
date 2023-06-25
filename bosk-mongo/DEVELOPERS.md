## Bosk-mongo developer's guide

This guide is intended for those interested in contributing to the development of the `bosk-mongo` module.
(The guide for developers _using_ the the module is [USERS.md](../docs/USERS.md).)

### Introduction

`MongoDriver` is an implementation of the `BoskDriver` interface,
which permits users to submit updates to the bosk state.
As with any `BoskDriver`, the changes are applied asynchronously,
and some time later, the user can perform a read of the bosk and will see the updates.

Note, in particular, that `BoskDriver` is not concerned with read operations.
In bosk, all reads are served from the in-memory replica of the bosk state via `Reference.value()`,
and they never perform any database operations or other I/O.
Consequently, `MongoDriver` is concerned only with
(1) initializing the bosk's in-memory replica of the state from the database, and
(2) implementing state updates.

Like all drivers, `MongoDriver` acts as a stackable layer on top of another _downstream_ `BoskDriver`.
Drivers are expected to implement the `BoskDriver` semantics, describing the desired updates to the downstream driver.
From this description alone, a driver that simply forwarded all method calls to the downstream driver would be a valid implementation, but not an interesting one.
Real drivers aim to imbue a bosk with additional attributes it would not otherwise have.
In the case of `MongoDriver`, these additional attributes are:
- _replication_: multiple bosks can connect to the same MongoDB database, and any updates made by one bosk will be reflected in all of them; and
- _persistence_: `MongoDriver` allows an application to be shut down and restarted, and will find the bosk restored to the state it had at the time it shut down.

`MongoDriver` achieves these goals by implementing each update in two steps:
1. The bosk update is translated into a database update, and written to MongoDB. (It is _not_ forwarded to the downstream driver!)
2. Change stream events from MongoDB are translated back into bosk updates, and sent to the downstream driver.

Replication is achieved naturally by MongoDB when two or more `MongoDriver`s connect to the same database,
since the change stream will include all updates from all drivers; and
to implement persistence,
`MongoDriver` reads the initial bosk state from the database during `Bosk` initialization (in the `initialRoot` method).

While conceptually simple, the real complexity lies in fault tolerance.
An important goal of `MongoDriver` is that if the database is somehow damaged and then repaired,
`MongoDriver` should recover and resume normal operation without any intervention.
This means `MongoDriver` must deal with a multitude of error cases and race conditions,
which add complexity to what would otherwise be a relatively straightforward interaction with MongoDB.
The MongoDB client library itself does an admirable job of fault tolerance on its own for database reads, writes, and DDL operations;
but once the change stream is added to the mix, things get complicated,
because the change stream cannot operate in isolation:
change stream initialization and error handling must be coordinated with database reads in order to make sure no change events are missed.

To manage this complexity,
a `MongoDriver` is actually composed of several objects with different responsibilities.
The happy-path database interactions are implemented by a `FormatDriver` object,
which rests on a kind of framework shell object called `MainDriver` that deals with
initialization, error handling, logging, change stream management, database transactions, and coordination between threads.
When anything goes awry, `MainDriver` can discard and replace the `FormatDriver` object,
which allows `FormatDriver` to be largely ignorant of fault tolerance concerns:
`FormatDriver` can simply throw exceptions when errors occur, and let `MainDriver` to handle the recovery operations.

The `MainDriver` functionality is further divided into three areas:
- the change stream cursor lifecycle is managed by `ChangeReceiver`, which houses the background thread that executes all change stream operations and event handling;
- the `BoskDriver` `submit` methods are implemented by `MainDriver` itself; and
- the `MainDriver.Listener` connects `ChangeReceiver` to `MainDriver` so `MainDriver` can respond to any errors that occur on the background thread.

Whenever possible, all error conditions handled internally are signaled by checked exceptions,
meaning the Java compiler can help check that we have handled them all.
In general, we do not shy away from defining new types of exceptions,
erring on the side of adding a new exception for clarity rather than reusing an existing one.

Testing is performed by JUnit 5 tests using Testcontainers and Toxiproxy.
Testcontainers allows us to verify our database interactions against an actual MongoDB database,
while Toxiproxy allows us to induce various network errors and verify our response to them.
(Mocks are not generally suitable for testing error cases,
since the precise exceptions thrown by the client library are not well documented,
and since they are runtime exceptions, we get no help from the Java compiler.)
We have developed a few of our own JUnit 5 extensions to make this testing more convenient,
such as `@ParametersByName` and `@DisruptsMongoService`.

### TODO: Points to cover
(This document is a work in progress.)

- Basic operation
- Reads always come from memory. That's always true in Bosk, and a driver can't change that even if it wanted to.
- Driver operations lead to database operations; they are not forwarded downstream
	- Translated from bosk to MongoDB by a `FormatDriver`
	- `initialRoot` is a special case that will need to be documented separately
- Change events bring the database operations back to `MongoDriver` via `ChangeReceiver`, which forwards them to `FormatDriver`
	- From there, they are translated from MongoDB back to Bosk and forwarded to the downstream driver
- With a single bosk interacting with the database, all of this makes little difference, but
	- You get persistence/durability
	- You get replication for free!
- The cursor lifecycle
- Two different lifetimes: permanent and cursor
- Responsibilities of the background thread
- `FormatDriver`
- Division of responsibilities from the draft Principles of Operation doc
- Emphasis on error handling
- General orientation toward checked exceptions for exceptions that are not visible to the user
- `initialRoot` is complicated; explain why from the block comment
- Logging philosophy from block comment
- MongoDB semantics
	- Opening the cursor, then reading the current `revision` using read concern `LOCAL` ensures events aren't missed
	- FormatDriver should discard events that occurred after the cursor was opened but before the revision from the initial read
- Mongo client (aka "driver") throws runtime exceptions, which makes robust error handling tricky
- Disconnected state is required to ensure we don't write to the database if we've lost our understanding of the database state

### Major components

A `MongoDriver` instance comprises a small cluster of objects that cooperate to deliver the desired functionality.

The actual core implementation of driver semantics is mostly contained in `FormatDriver` implementations,
which handle the translation of bosk operations to and from MongoDB operations.

The top-level coordination is implemented by `MainDriver`, which serves as a kind of harness for the rest of the objects.
`MainDriver` is concerned with initialization, error handling, and coordination between threads.

The management of the change stream, by which `MongoDriver` learns of changes to the database,
is governed by `ChangeReceiver`, which houses the background thread responsible for
opening the cursor, waiting for events, forwarding them to the `FormatDriver` (via the `ChangeListener` interface),
and handling cursor-related exceptions.
The `MainDriver` instantiates the `ChangeReceiver` and coordinates with it to ensure all change events and error conditions are handled appropriately.

### The `MongoDriver` interface

The `MongoDriver` interface extends the `BoskDriver` interface with two additional methods, described below.

#### `refurbish`

The `refurbish` method supports schema evolution by
rewriting the entire database contents to bring them up to date.
Users of `MongoDriver` use `MongoDriverSettings` to configure their preferred format options,
but if the database already exists, its format must take precedence.
The intent of the `refurbish` method is to overwrite the database contents so they are freshly serialized using the preferred format.

Conceptually, calling `bosk.driver().refurbish()` is very much like
calling `bosk.driver().submitReplacement(bosk.rootReference(), bosk.rootReference().value())`,
thereby replacing the entire database contents with a freshly serialized version.
However, there are three major differences between `refurbish` and this read-then-write operation:

1. `refurbish` is atomic, while read-then-write is not, and could be subject to the _lost update problem_;
2. `refurbish` changes the database to the preferred format specified in the `MongoDriverSettings`, while read-then-write preserves the current format; and
3. `refurbish` updates and modernizes the internal metadata fields, while read-then-write does not.

The implementation of `refurbish` uses a multi-document transaction to read, deserialize, re-serialize, and write the database contents atomically.

#### `close`

The `close` method shuts down a `MongoDriver` when the application is finished with it.
This is not often used in application, where usually a `Bosk` is a singleton
whose lifetime coincides with that of the application.
However, `close` is useful in unit tests,
to stop the operation of one driver so it doesn't unduly interfere with the next one.

`close` is done on a best-effort basis, and isn't guaranteed to cleanly shut down the driver in all cases.
In some cases, `close` can initiate an asynchronous shutdown that may not complete before `close` returns.

### The `FormatDriver` interface

The `FormatDriver` interface is a further extension of the `MongoDriver` interface
that describes the objects that do all _format-specific_ logic.
By "format" here, we mean the exact way in which the bosk state is mapped to and from database contents.
In order to allow for the evolution of the database format over time,
`MongoDriver` is separated into two primary pieces:

- the subclasses of `FormatDriver`, which implement the `BoskDriver` methods in terms of database write operations, and translate change events from the database into corresponding bosk update operations; and
- the `MainDriver` and `ChangeReceiver` classes, which implement format-independent logic for initialization, fault tolerance, thread synchronization, error handling, and logging, as well as common logic for the `initialRoot` and `refurbish` operations.

There are two ways in which a `FormatDriver` builds on the functionality of other `MongoDriver` classes, described below.

#### `loadAllState` and `initializeCollection`

The first feature that distinguishes a `FormatDriver` from a `MongoDriver`
is that two of the most challenging `MongoDriver` methods don't need to be implemented at all.

There are two `MongoDriver` methods that present special challenges to `FormatDriver`:
- `refurbish` requires cooperation between two potentially different formats, so it can't be implemented by any individual `FormatDriver`; and
- `initialRoot` requires cooperation between loading the database and opening a change stream cursor, and most of the complexity here is not format-specific.

For these reasons, `FormatDriver` does not implement these two methods,
but instead implements two different methods:
- `loadAllState` performs a database read of the entire bosk state and metadata, and returns it in a `StateAndMetadata` object; and
- `initializeCollection` performs a database write of the entire bosk state and metadata.

Given these two methods, `MainDriver` does the following:
- `refurbish` calls `loadAllState` on the detected `FormatDriver` and `initializeCollection` on the desired `FormatDriver`, thereby translating the format from one to the other; and
- `initialRoot` calls `loadAllState` on the detected `FormatDriver` and, if the state does not exist, delegates `initialRoot` to the downstream driver, and passes the result to `initializeCollection`.

In this way, two complex bosk operations filled with error handling and inter-thread coordination
are reduced to simpler database operations easily implemented by the `FormatDriver`.

#### `onEvent` and `onRevisionToSkip`

The second feature that distinguishes a `FormatDriver` from a `MongoDriver`
is that `FormatDriver` is expected to respond to change stream events.

The `onEvent` method is called (by the `ChangeReceiver`, described below)
for each event received.
The `FormatDriver` is expected to perform the appropriate operations in order to communicate the change to the downstream driver;
or else it can throw `UnprocessableEventException` and cause the `ChangeReceiver` to reset and reinitialize the replication from scratch.

The `onRevisionToSkip` method is called whenever the state is loaded via `loadAllState`.
This communicates to the `FormatDriver` that subsequent events should be ignored if they are older than the revision found when the state was read from the database.

### `ChangeReceiver` and `ChangeListener`

If change stream events are the lifeblood of `MongoDriver`, then `ChangeReceiver` is its heart.
`ChangeReceiver` houses the background thread that manages the `MongoChangeStreamCursor` objects,
running a continual loop that initializes the cursor, waits for events, feeds them to a `ChangeListener`
and eventually closes the cursor if there's an error, at which point the loop starts over with a new cursor.

In a perfect world, there would be a clean division of responsibilities between `ChangeReceiver` and `MainDriver`.
`ChangeReceiver` processes change events; `MainDriver` implements the `BoskDriver` methods using database reads and writes.
`ChangeReceiver` runs on a background thread; `MainDriver` runs on the foreground threads (the application threads that call the `BoskDriver` methods).

In reality, the division can't be this clean, for two main reasons.

First, all format-specific operations, whether database reads or writes or change stream events,
are handled by `FormatDriver`.
The division we describe here is intentionally absent from `FormatDriver`,
where separating these two concerns would do more harm than good.

Second, reading the database state is inextricably linked with change events,
because when the bosk state is loaded into memory,
it's crucial to ensure that every subsequent update is applied, and none are missed.
This means the opening of a new cursor must be coordinated with the load of the bosk state.
Furthermore, certain other change stream occurrences, like an `INVALIDATE` event or a `NoSuchElementException` exception,
affect how the driver methods behave;
for example, when the connection to the database is lost,
calls to driver methods may attempt to reconnect, or may throw an exception.

As a result, there are actually four divisions of responsibilities:
1. All format-specific logic is in `FormatDriver`
2. For format-independent logic:
- All change stream cursor lifecycle logic is in `ChangeReceiver`
- Everything that affects driver method implementation logic is in `MainDriver`
	- The foreground thread logic, implementing the driver methods, is in `MainDriver` itself
	- The background thread logic, implementing the _effects_ of change stream occurrences on driver methods, is in `MainDriver.Listener`, which implements `ChangeListener`

#### The cursor lifecycle

The lifetime of a `MongoDriver` is subdivided into a sequence of (ideally just one) connect-process-disconnect cycles.
The whole cycle is initiated by a single background thread,
and so these operations have almost no possibility for race conditions.

When something goes wrong, and we must reconnect by closing the current cursor and opening a new one,
this operation naturally shares the same logic with initialization and shutdown,
because a reconnection is simply a shutdown followed by an initialization.

Certain objects, such as `MainDriver` and `ChangeReceiver`, have a lifetime that coincides with that of the `Bosk`,
while other objects, such as `FormatDriver` and `FlushLock`, have a lifetime that coincides with that of the cursor.
