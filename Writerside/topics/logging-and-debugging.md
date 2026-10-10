# Logging and debugging

## Logging {id="logging"}

Kamel logs through SLF4J. Without an SLF4J binding on the classpath you see a one-time warning from SLF4J and no
log output. Add a binding to see it, for example Logback:

```kotlin
dependencies {
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
}
```

These loggers exist:

| Logger name | Logs |
|-------------|------|
| `ImapClient/<username>@<host>:<port>` | Opening and closing of client connections |
| `ImapFolder/<full name>` | Opening and closing of folder connections, responses that could not be parsed |
| `es.jvbabi.overmail.core.ImapClient` | Folder list entries that could not be parsed |

Connection handling is logged on `DEBUG`, problems on `WARN` and `ERROR`. A `logback.xml` that shows everything
from Kamel's connection pools:

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %-5level %logger - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="DEBUG">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```
{ignore-vars="true"}

## Protocol trace {id="debug"}

`debug = true` prints every line Kamel sends and receives to standard output. This does not go through SLF4J.

```kotlin
val client = ImapClient(
    host = "imap.example.com",
    port = 993,
    auth = auth,
    debug = true,
)
```

```text
SI 1760112000123 > A000 LOGIN "someone@example.com" "..."
SI 1760112000123 < A000 OK Logged in
SI 1760112000123 > A001 LIST "" "*"
SI 1760112000123 < * LIST (\HasNoChildren) "/" "INBOX"
SI 1760112000123 < A001 OK List completed
```

The number after `SI` identifies the connection. `>` marks what was sent, `<` what was received.

> The trace contains everything that is sent, in clear text. That includes the password of the `LOGIN` command
> and the content of your mails. Use it for development only and never paste it into a bug report unredacted.
{style="warning"}

## Reporting a problem

When a server answers something Kamel does not understand, these two things make a
[bug report](https://github.com/Overmail/kamel/issues) actionable:

- the log output with [`dumpMailOnError`](fetching-messages.md#dump) enabled,
- the relevant lines of the protocol trace, with credentials and personal data removed.
