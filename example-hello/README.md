### Hello World example project

Ok, this app doesn't do much,
but it does demonstrate how easy it is to get basic bosk functionality in a Spring Boot 3 project.

#### Jackson `ObjectMapper` configuration

The `bosk-spring-boot-3` module automatically configures Jackson
to do JSON serialization and deserialization of the bosk state tree objects,
via the `bosk-jackson` module.

#### Automatic `ReadContext`

A bosk `ReadContext` provides a lightweight thread-local snapshot of the bosk state
for the duration of an operation.
The `bosk-spring-boot-3` module automatically establishes a `ReadContext` around every HTTP servlet method,
using the `ReadContextFilter` class.
(This can be disabled by adding the line `bosk.web.read-context: false` to `application.properties`.)

#### Service endpoints

By setting `bosk.web.service-path: /bosk` in `application.properties`,
the `bosk-spring-boot-3` module creates `GET`, `PUT`, and `DELETE` endpoints
that allow users to view and modify the bosk contents over HTTP.
