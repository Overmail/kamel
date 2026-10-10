# Tutorial: Log in to Gmail or Outlook with OAuth

Gmail and Microsoft 365 no longer accept an account password over IMAP. They expect an OAuth 2.0 access token. In
this tutorial you connect to such a mailbox with `ImapClient.Auth.BearerAuth`.

## Before you start

- You have an OAuth 2.0 access token for the mailbox. Getting one is not part of Kamel: use the OAuth library or
  flow your application already has.
- The token was issued with the IMAP scope of the provider:

| Provider | Host | Port | Scope |
|----------|------|------|-------|
| Gmail | `imap.gmail.com` | `993` | `https://mail.google.com/` |
| Outlook / Microsoft 365 | `outlook.office365.com` | `993` | `https://outlook.office.com/IMAP.AccessAsUser.All` |

## Connect with the token

<procedure title="Log in with XOAUTH2" id="login">
<step>

Pass the mail address and the bare access token. Do not add the `Bearer` prefix, Kamel does that:

```kotlin
val client = ImapClient(
    host = "imap.gmail.com",
    port = 993,
    auth = ImapClient.Auth.BearerAuth(
        username = "someone@gmail.com",
        bearer = accessToken,
    ),
)
```

</step>
<step>

Check the login before you do anything else:

```kotlin
try {
    client.testConnection()
} catch (e: ImapCommandException) {
    println("The server rejected the token: ${e.response}")
}
```

A rejected token, for example an expired one or one without the IMAP scope, ends in an `ImapCommandException`.

</step>
<step>

From here on, everything works as with a password:

```kotlin
client.use {
    it.getFolders().forEach { folder -> println(folder.fullName) }
}
```

</step>
</procedure>

## Deal with expiring tokens

Access tokens are short-lived, often one hour. Kamel sends the token every time it opens a new connection, and it
opens connections on demand. So a client that was created with a token that has expired since will fail as soon as
it needs another connection.

Refresh the token in your application and create a new client with it:

```kotlin
class Mailbox(private val tokens: TokenProvider) {
    private var client: ImapClient? = null
    private var tokenOfClient: String? = null

    suspend fun client(): ImapClient {
        val token = tokens.validAccessToken()
        if (token != tokenOfClient) {
            client?.close()
            client = ImapClient(
                host = "imap.gmail.com",
                port = 993,
                auth = ImapClient.Auth.BearerAuth("someone@gmail.com", token),
            )
            tokenOfClient = token
        }
        return client!!
    }
}
```

`TokenProvider` stands for whatever your application uses to refresh tokens.

> Folders, mails and idle folders belong to the client they came from. After you replaced the client, get them
> again from the new one.
{style="warning"}

## What you learned

- `BearerAuth` logs in with SASL `XOAUTH2`. More in [](authentication.md).
- A client keeps the token it was created with.
