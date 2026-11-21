# Bruno development bridge

The optional companion enables **direct Spring MVC invocation** and **manual scheduled / XXL-job execution** in a running development application. It is separate from normal HTTP requests. It is disabled by default, binds to `127.0.0.1`, and requires a token of at least 24 characters.

## Install

From Bruno's API explorer overflow menu, open **Development bridge…** and choose **Export starter…**. The export contains `bruno-spring-bridge.jar` and its Maven POM. Install both together:

```sh
mvn install:install-file -Dfile=bruno-spring-bridge.jar -DpomFile=bruno-spring-bridge.pom
```

Add the dependency to a development Maven profile:

```xml
<profile>
  <id>bruno-dev</id>
  <dependencies>
    <dependency>
      <groupId>com.usebruno</groupId>
      <artifactId>bruno-spring-bridge</artifactId>
      <version>0.4.9</version>
      <scope>runtime</scope>
    </dependency>
  </dependencies>
</profile>
```

For Gradle with the Spring Boot plugin, add `mavenLocal()` to repositories and:

```kotlin
developmentOnly("com.usebruno:bruno-spring-bridge:0.4.9")
```

Use a development application profile to enable the companion:

```yaml
bruno:
  bridge:
    enabled: true
    port: 19090
    token: ${BRUNO_BRIDGE_TOKEN}
```

Generate a token in **Development bridge…**, copy it into the application's run configuration as `BRUNO_BRIDGE_TOKEN`, and restart the application with that development profile. Set the IDE bridge URL to `http://127.0.0.1:19090`, enter the same token, and use **Test connection**, then **Save Bridge Connection**. The IDE stores the token in its credential store. Connection metadata is kept in IDE settings per project.

The starter requires Java 21 or newer and a Spring MVC servlet application. Integration tests cover Spring Boot 3.4.0 / Spring 6.2.0 and the same baseline-compiled bridge on Boot 4.1.1 / Spring 7.0.9. The companion uses Spring Test's servlet request/response implementations as a runtime dependency; the supplied POM is required to resolve them. Spring Boot dependency management should align Spring libraries with the application.

## Invoke a controller

Open the request's **Advanced** section and enable **Invoke through development bridge**. The primary action becomes **Invoke** and the request badge identifies the bridge transport. The optional principal field supplies a `java.security.Principal` name for argument binding.

The bridge resolves the active route through the application's `RequestMappingHandlerMapping`, then invokes its `RequestMappingHandlerAdapter`. It skips servlet filters and handler interceptors. It retains Spring argument conversion/validation, exception resolvers, controller advice, and method/service proxies. Method-level security can therefore still reject an invocation. It does not synthesize an application-specific authenticated session or custom security principal.

For requests opened from discovery or a gutter marker, the bridge checks the expected controller before invoking it. Configure the connection for the application that owns that controller. Use a distinct bridge port for each running application.

Path variables, repeated query parameters, JSON/form/multipart bodies, cookies, optional principals, and asynchronous controller results are supported. Request bodies are limited to 8 MiB; application response buffering is limited to 16 MiB; the async controller wait is limited to 30 seconds. The usual IDE response preview limit still applies. App-specific servlet-container behavior and persistent HTTP sessions are not reproduced by the mock request.

Stop cancels the IDE operation and any local script worker. Application code already invoked inside the running JVM may continue; safely terminating arbitrary Java methods is not attempted. Copy cURL exports the original HTTP request, without the private bridge token or bypass behavior.

## Trigger tasks

Open **Scheduled / XXL tasks…** from the API explorer menu. Refresh reads annotated methods from the running application. Select a handler, optionally provide an XXL parameter string, then choose **Run selected task**.

- `@Scheduled` methods are invoked once through their Spring bean. They must have no arguments.
- `@XxlJob` handlers are discovered without requiring XXL in applications that do not use it. The XXL parameter is installed in `XxlJobContext` for the invocation and the previous thread context is restored afterward. Tests use the real XXL-Job 2.4.1 library.
- Only discovered annotated handlers can be invoked; arbitrary class/method names are not accepted.
- Application logging and service advice remain in the application. Future/reactive return values from scheduled handlers are not awaited as job-completion results.

## Build and verify

```sh
./gradlew :spring-bridge:test :spring-bridge:jar :spring-bridge:generatePomFileForBridgePublication
./gradlew -PbridgeModern :spring-bridge:test
```

`buildPlugin` embeds the starter JAR and its POM for export from the IDE. The starter can also be installed locally with `./gradlew :spring-bridge:publishToMavenLocal`.
