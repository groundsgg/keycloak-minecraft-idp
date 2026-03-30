# Keycloak Minecraft Identity Provider

A Keycloak Identity Provider plugin that enables authentication via Microsoft/Xbox OAuth2 and stores the Minecraft player name as the primary username.

## Features

- **Minecraft Java Edition Support** – Authenticate players with their Minecraft Java Edition account
- **Bedrock Edition Fallback** – Players without Java Edition can use their Xbox Gamertag
- **Automatic Username Sync** – Keycloak username is automatically set to the Minecraft player name
- **Rich User Attributes** – Stores Minecraft UUID, edition type, and Xbox Gamertag
- **Seamless Integration** – Works like any other Keycloak Identity Provider

## Quick Start

### Download

Download the latest JAR from the [Releases](https://github.com/groundsgg/keycloak-minecraft/releases) page.

Or build from source:

```bash
./gradlew shadowJar
```

### Installation

1. Copy the JAR to your Keycloak providers directory:

```bash
cp keycloak-minecraft.jar /opt/keycloak/providers/
```

2. Rebuild Keycloak:

```bash
/opt/keycloak/bin/kc.sh build
```

3. Restart Keycloak

## How It Works

```mermaid
sequenceDiagram
    participant User
    participant Keycloak
    participant Microsoft as Microsoft OAuth
    participant Xbox as Xbox Live
    participant MC as Minecraft API

    User->>Keycloak: Click "Sign in with Minecraft"
    Keycloak->>Microsoft: Redirect to OAuth
    Microsoft-->>Keycloak: Access Token
    Keycloak->>Xbox: Authenticate with Xbox Live
    Xbox-->>Keycloak: Xbox Token + Gamertag
    Keycloak->>Xbox: Get XSTS Token
    Xbox-->>Keycloak: XSTS Token
    Keycloak->>MC: Authenticate with Minecraft
    MC-->>Keycloak: Minecraft Token
    Keycloak->>MC: Check entitlements (/entitlements/mcstore)
    MC-->>Keycloak: Owned products
    alt Owns Java Edition
        Keycloak->>MC: Get Profile
        MC-->>Keycloak: Username + UUID
        Keycloak-->>User: Logged in as Java Edition player
    else Bedrock / no Java Edition
        Keycloak-->>User: Logged in as Xbox Gamertag (Bedrock)
    end
```

## Prerequisites

- JDK 21 installed locally and available on `PATH`
- A Microsoft Azure App Registration

Gradle toolchain auto-download is disabled in this repository. Builds require a locally installed JDK 21 and will not provision one automatically.

### Azure App Registration

You need a Microsoft Azure App Registration:

1. Go to [Azure Portal](https://portal.azure.com/) → "App registrations"
2. Create a new App Registration
3. Configure:
   - **Redirect URI**: `https://your-keycloak-url/realms/{realm}/broker/minecraft/endpoint`
   - **API Permissions**: Add `XboxLive.signin` (delegated)
4. Create a Client Secret under "Certificates & secrets"

## Configuration in Keycloak

1. Go to your Realm → Identity Providers
2. Click "Add provider" → "Minecraft"
3. Configure:
   - **Client ID**: The Application (client) ID from your Azure App
   - **Client Secret**: The Client Secret from your Azure App

## User Attributes

After successful authentication, the following attributes are stored:

| Attribute            | Description                                                        |
|----------------------|--------------------------------------------------------------------|
| `username`           | Minecraft player name or Xbox Gamertag (primary Keycloak username) |
| `minecraft_username` | The Minecraft player name or Xbox Gamertag                         |
| `minecraft_edition`  | `java` or `bedrock` - which identity path was used for login       |
| `minecraft_uuid`     | The Minecraft UUID (only for Java Edition)                         |
| `xbox_gamertag`      | The player's Xbox Gamertag                                         |
| `xbox_user_id`       | The Xbox User ID (if available)                                    |

### Java Edition vs. Bedrock Edition

- **Java Edition**: Players with Minecraft Java Edition get their Java player name and UUID
- **Bedrock Edition**: Players without Java Edition (Bedrock only) get their Xbox Gamertag as their username

If a player owns both editions, the plugin resolves them as `java`. `minecraft_edition` reflects the identity path used for login, not the full set of editions the account owns.

## Local Development

```bash
# Verify Java 21 is installed
java -version

# Build the provider JAR
./gradlew shadowJar

# Start Keycloak with the development compose file
cd docker
docker compose up -d
```

`java -version` must report Java 21 before running the Gradle build.

The development compose file mounts `../build/libs/keycloak-minecraft.jar` into the upstream Keycloak image and runs `kc.sh build` before `start-dev`.

Keycloak will be available at http://localhost:8080.
- **Admin Username**: admin
- **Admin Password**: admin

## Troubleshooting

### "This Microsoft account does not own Minecraft Java Edition"

The user doesn't have Minecraft Java Edition on their Microsoft account. They will be authenticated with their Xbox Gamertag instead.

### Xbox Live Errors

| Error Code   | Meaning                                          |
|--------------|--------------------------------------------------|
| 2148916227   | Account banned from Xbox Live                    |
| 2148916233   | Microsoft account doesn't have an Xbox account   |
| 2148916235   | Xbox Live is not available in the user's country |
| 2148916236/7 | Adult verification required (South Korea)        |
| 2148916238   | Child account - needs to be added to a family    |

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## Acknowledgments

- [Keycloak](https://www.keycloak.org/) – Open Source Identity and Access Management
- [Minecraft Authentication Documentation](https://minecraft.wiki/w/Microsoft_authentication) – Community documentation of the auth flow
