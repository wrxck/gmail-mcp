# Gmail MCP Server

A [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) server that provides read-only Gmail access to AI assistants like [Claude Code](https://docs.anthropic.com/en/docs/claude-code). Built in Java 21 using the official [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk).

## Features

| Tool | Description |
|------|-------------|
| `list_emails` | List recent emails with optional query and label filter |
| `read_email` | Read the full content of an email by message ID (includes attachment metadata) |
| `search_emails` | Search emails using [Gmail search syntax](https://support.google.com/mail/answer/7190) |
| `get_attachment` | Fetch attachment content by message ID and attachment index |
| `list_labels` | List all Gmail labels |

All access is **read-only** (`GMAIL_READONLY` scope). The server cannot send, modify, or delete emails.

Attachments are discovered via `read_email` (which includes an `attachments` array with metadata) and fetched individually via `get_attachment`:

- **Text attachments** (`text/*`) return decoded content inline
- **Image attachments** (`image/*`, up to 10 MB) are returned as inline `ImageContent` for visual analysis
- **Other binary attachments** (PDF, documents, etc.) are saved to `~/.gmail-mcp/attachments/<messageId>/` and the file path is returned

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

This produces a single uber-jar at `target/gmail-mcp-1.2.0.jar`.

### 3. Authenticate

```bash
java -jar target/gmail-mcp-1.2.0.jar --auth
```

This opens your browser for Google OAuth consent. After authorizing, credentials are saved to `~/.gmail-mcp/tokens/` and automatically refreshed on expiry. You only need to do this once.

### 4. Register with Claude Code

```bash
claude mcp add --scope user --transport stdio gmail -- \
  java -jar /path/to/gmail-mcp-1.2.0.jar
```

Restart Claude Code. The 4 Gmail tools will be available immediately.

## Usage examples

Once registered, you can ask Claude:

- "Check my recent emails"
- "Search for emails from john@example.com about the project proposal"
- "Read email `<message-id>`"
- "What labels do I have?"
- "Find unread emails in my inbox from this week"

## Security

Email content is untrusted third-party data that gets returned directly into an LLM's context. A malicious email could contain text designed to manipulate the agent into taking unintended actions. This server applies defense-in-depth mitigations:

- **Random content boundaries** — Each tool response generates a cryptographically random boundary string (via `SecureRandom`). Untrusted fields (`from`, `subject`, `snippet`, `body`, `filename`, `content`) are wrapped with this boundary so the LLM can distinguish email data from system structure. Since the boundary is unpredictable, an attacker cannot embed it in their email to escape the content region.
- **Security context preamble** — Every email tool response includes a leading text block explaining the boundary token and instructing the LLM to treat bounded content as data only.
- **Tool description warnings** — The `list_emails`, `read_email`, `search_emails`, and `get_attachment` tool descriptions explicitly warn that returned content is untrusted.
- **Body truncation** — Email bodies exceeding 50,000 characters are truncated with a `[TRUNCATED]` indicator to prevent oversized payloads from overwhelming context. Attachment content is truncated at 100,000 characters.
- **Typed attachment handling** — `text/*` attachments return decoded content inline. `image/*` attachments (up to 10 MB) are returned as `ImageContent` for vision analysis. All other binary attachments are saved to `~/.gmail-mcp/attachments/` with sanitized filenames, and the file path is returned. Filenames from emails are sanitized to prevent path traversal attacks.

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
  attachments/           # Saved binary attachments (auto-managed, rwx------)
    <messageId>/
      <sanitized-filename>
```

## Development

```bash
# Build
mvn clean package

# Run tests
mvn test

# Run auth flow
java -jar target/gmail-mcp-1.2.0.jar --auth

# Run server (blocks on stdio — use with Claude Code or an MCP client)
java -jar target/gmail-mcp-1.2.0.jar
```

### Project structure

```
src/main/java/com/gmail/mcp/
  GmailMcpServer.java    # Main class, MCP tool definitions
  GmailAuth.java          # OAuth flow, credential management
  GmailClient.java        # Gmail API wrapper
  ContentSanitizer.java   # Prompt injection mitigations

src/test/java/com/gmail/mcp/
  ContentSanitizerTest.java    # Boundary wrapping, truncation, sanitization
  ResponsePipelineTest.java    # End-to-end response pipeline (email vs label tools)
  GmailClientTest.java         # HTML stripping, base64 decoding, body extraction
  GmailMcpServerTest.java      # Argument parsing
```

## License

[MIT](LICENSE)
