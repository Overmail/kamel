# Authentication

The `auth` parameter of `ImapClient` decides how every connection of that client logs in. There are two ways.

## Username and password {id="basic"}

`ImapClient.Auth.BasicAuth` sends the IMAP `LOGIN` command:

```kotlin
val auth = ImapClient.Auth.BasicAuth(
    username = "someone@example.com",
    password = System.getenv("IMAP_PASSWORD"),
)
```

Use it for providers that accept a password or an app password over IMAP.

> Passwords that contain a double quote or a backslash are not escaped yet and make the login fail. See
> [](known-limitations.md).
{style="warning"}

## OAuth 2.0 access token {id="oauth"}

`ImapClient.Auth.BearerAuth` logs in with SASL `XOAUTH2`, as Gmail and Outlook expect it:

```kotlin
val auth = ImapClient.Auth.BearerAuth(
    username = "someone@gmail.com",
    bearer = accessToken,
)
```

`username`
: The mail address of the mailbox.

`bearer`
: The bare access token, without the `Bearer` prefix.

Kamel does not obtain or refresh tokens. The token is used whenever the client opens a new connection, so create a
new client once it has expired. [](tutorial-oauth.md) shows the whole setup.

## When the login fails

Logging in happens when a connection is opened, see [](connecting.md). If the server rejects the credentials, the
call that needed the connection throws an `ImapCommandException`.

> For `BasicAuth`, `ImapCommandException.command` and the exception message contain the `LOGIN` command, including
> the password. Do not log them for a failed login. Log `response` instead.
{style="warning"}

## Credentials in logs

`toString()` of both classes masks the secret:

```kotlin
println(ImapClient.Auth.BasicAuth("someone@example.com", "secret"))
// BasicAuth(username=someone@example.com, password=***)
```

The [debug output](logging-and-debugging.md#debug) is different: it prints what is sent to the server, and that
includes the credentials.
