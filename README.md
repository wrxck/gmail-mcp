# Gmail MCP Server

A [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) server that provides read-only Gmail access to AI assistants like [Claude Code](https://docs.anthropic.com/en/docs/claude-code). Built in Java 21 using the official [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk).

## Features

| Tool | Description |
|------|-------------|
| `list_emails` | List recent emails with optional query and label filter |
| `read_email` | Read the full content of an email by message ID |
| `search_emails` | Search emails using [Gmail search syntax](https://support.google.com/mail/answer/7190) |
| `list_labels` | List all Gmail labels |

All access is **read-only** (`GMAIL_READONLY` scope). The server cannot send, modify, or delete emails.

## Prerequisites

- Java 21+
- Maven 3.6+
- A Google Cloud project with the Gmail API enabled
- OAuth 2.0 credentials (Desktop application type)

## Setup

### 1. Google Cloud credentials

1. Go to the [Google Cloud Console](https://console.cloud.google.com/)
2. Create a project (or select an existing one)
3. Enable the **Gmail API** under APIs & Services
4. Go to **Credentials** > **Create Credentials** > **OAuth client ID**
5. Select **Desktop app** as the application type
6. Download the JSON file and save it as `~/.gmail-mcp/client_secret.json`

### 2. Build

```bash
git clone https://github.com/wrxck/gmail-mcp.git
cd gmail-mcp
mvn clean package
```

This produces a single uber-jar at `target/gmail-mcp-1.0.0.jar`.

### 3. Authenticate

```bash
java -jar target/gmail-mcp-1.0.0.jar --auth
```

This opens your browser for Google OAuth consent. After authorizing, credentials are saved to `~/.gmail-mcp/tokens/` and automatically refreshed on expiry. You only need to do this once.

### 4. Register with Claude Code

```bash
claude mcp add --scope user --transport stdio gmail -- \
  java -jar /path/to/gmail-mcp-1.0.0.jar
```

Restart Claude Code. The 4 Gmail tools will be available immediately.

## Usage examples

Once registered, you can ask Claude:

- "Check my recent emails"
- "Search for emails from john@example.com about the project proposal"
- "Read email `<message-id>`"
- "What labels do I have?"
- "Find unread emails in my inbox from this week"

## Architecture

```
Claude Code  <--stdio (JSON-RPC)-->  gmail-mcp.jar  <--HTTPS-->  Gmail API
                                          |
                                    ~/.gmail-mcp/
                                      client_secret.json
                                      tokens/StoredCredential
```

- **Transport**: stdio (Claude Code spawns the server as a subprocess)
- **OAuth**: `GoogleAuthorizationCodeFlow` with `FileDataStoreFactory` handles token persistence and automatic refresh
- **Logging**: All logs go to stderr (stdout is reserved for MCP JSON-RPC)

## Configuration

Credentials are stored in `~/.gmail-mcp/` with restricted permissions (`rwx------`):

```
~/.gmail-mcp/
  client_secret.json    # OAuth client credentials (you provide this)
  tokens/               # Stored OAuth tokens (auto-managed)
    StoredCredential
```

## Development

```bash
# Build
mvn clean package

# Run auth flow
java -jar target/gmail-mcp-1.0.0.jar --auth

# Run server (blocks on stdio — use with Claude Code or an MCP client)
java -jar target/gmail-mcp-1.0.0.jar
```

### Project structure

```
src/main/java/com/gmail/mcp/
  GmailMcpServer.java   # Main class, MCP tool definitions
  GmailAuth.java         # OAuth flow, credential management
  GmailClient.java       # Gmail API wrapper
```

## License

[MIT](LICENSE)
