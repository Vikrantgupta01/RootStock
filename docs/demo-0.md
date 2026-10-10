# Demo 0: Rootstock's platform, checked

**Story (about 2 minutes):** before any domain work, Rootstock stands on four real
services: Bedrock for the models, RDS for its data, Cognito for sign-in, and Langfuse for
tracing. One screen shows all of them answering right now, and links straight to the
latest trace. Underneath, the code is in enforced layers and the tests run against real
AWS without touching the application's data.

## Before the demo (about 2 minutes)

```bash
# Rootstock (port 8080), settings from rootstock-core/.env
cd rootstock-core
set -a && source .env && set +a && mvn spring-boot:run
#    The log says: No active profile set (one configuration, no profiles)

# The demo UI (port 5173)
cd frontend && npm run dev
```

Sign in at http://localhost:5173. Send one Chat message first, so Langfuse has a recent
trace to link to. If the Vinnies server is running (`vinnies/vinnies-mcp-server`), it
appears as a fifth check.

## Script

1. **Everything answering (40 s).** Open **Status**.
   - Result: green cards for **Bedrock** (the model answered "OK"), **RDS** (the schema's
     latest migration), **Cognito** (the pool's signing keys), **Langfuse** (keys accepted)
     and, if running, **MCP: vinnies** (its five tools), each with its latency.
   - *Say:* "These aren't configuration checks. Each card is a real round trip made just
     now. If a service is slow, its card turns red after 15 seconds instead of the page
     hanging."

2. **Straight to the trace (30 s).** On the Langfuse card, click **latest trace**.
   - Result: Langfuse opens on the most recent trace, such as the Chat message's
     `chat-response`, with the Bedrock generation, tokens and cost.
   - *Say:* "Every request is traced. From the status screen you're one click from seeing
     what the model was asked and what it cost."

3. **Refresh (15 s).** Click **Refresh**.
   - Result: the checks run again; the Langfuse link now points at an even newer trace if
     one arrived.

4. **How it's built (35 s, optional, in a terminal).**

   ```bash
   cd rootstock-core && set -a && source .env && set +a
   mvn verify
   ```

   - Result: unit tests, the architecture rules (ArchUnit: `core` never depends on
     `autoconfig` or `runtime`; nothing in Rootstock names a client), and an integration
     test that boots Rootstock against RDS in a **throwaway schema**, created for the run
     and dropped at the end.
   - *Say:* "Tests use real AWS, never the application's own data. The ones that cost
     money, real Bedrock calls, are tagged `live` and run only when asked for."

## If something is red

| Card | Usual cause |
| --- | --- |
| Bedrock | AWS credentials expired (refresh them), or `BEDROCK_MODEL` has no model access |
| RDS | Aurora is resuming from auto-pause: Refresh after 30 seconds |
| Cognito | No network, or a wrong `COGNITO_USER_POOL_ID` |
| Langfuse | `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` missing or wrong |
| MCP: vinnies | The Vinnies server isn't running on port 8081 |
