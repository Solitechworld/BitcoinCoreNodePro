# Bitcoin Core Node

A full Bitcoin Core node for Android, redesigned with a cyberspace/cyberpunk aesthetic:
dark void background, neon glowing borders, scanlines, blueprint grid, and bubble-style UI.

### 🔒 Circle Passcode (6-digit)
- **Circle dot input**: 6 filled/empty circles that animate as digits are entered
- **Bubble number pad**: Rounded keys with subtle neon border
- **Step-based setup**: Enter → Confirm flow with visual feedback
- **Biometric button**: Circular fingerprint icon with violet neon glow
- **Error animation**: Circles flash red on incorrect passcode

### 💾 Prune Options (10–30 GB)
- **Default: 10 GB** prune (was 5 GB)
- **Options**: 10 / 15 / 20 / 25 / 30 GB as bubble selectors
- Storage fit check warns if the device can't hold the chosen option

### 📅 Sync Year Display
- **"Sync year"** shown in the Chain info panel (like Bitcoin Core PC)
- **"Syncing year 2024"** shown during initial block download
- Uses block-height-to-year interpolation from known halving milestones

### 🖐️ Enhanced Biometrics
- Auto-prompts biometrics on lock screen arrival
- Circular biometric button with icon
- Falls back to device PIN/pattern if no biometrics enrolled
- Biometric required for transaction signing (toggle in Settings)

### 💼 All Original Bitcoin Core Functionalities Preserved
- Full Bitcoin Core 30.3 as native child process
- RPC console access
- Multiple wallet create/load/unload
- Wallet import (legacy wallet.dat migration)
- Descriptor wallet backup and restore
- Send/Receive with BIP-21 URI support
- Transaction history and coin control
- Peer management and mempool view
- Fast sync from UTXO snapshot
- Block explorer fallback (opt-in, privacy-disclosed)
- Tor support (Orbot)
- Network selection (mainnet/testnet/signet/regtest)
- Screen security (FLAG_SECURE)
- Background sync with wake lock

## Building

See [BUILD-ON-YOUR-MAC.md](BUILD-ON-YOUR-MAC.md) for native library build instructions.

For the Android app:
```bash
./gradlew assembleDebug
```

The debug APK will be in `app/build/outputs/apk/debug/`.

## Design System

### Colour Palette
| Token | Colour | Use |
|-------|--------|-----|
| Void | `#0A0E17` | Page background |
| Surface | `#111827` | Card background |
| SurfaceElevated | `#1A2236` | Raised card |
| Cyan | `#00F0FF` | Primary accent, mainnet |
| Green | `#00FF88` | Confirmed, incoming |
| Amber | `#FFB800` | Pending, warnings |
| Magenta | `#FF00FF` | Secondary accent |
| Red | `#FF3366` | Error, danger |
| Violet | `#7A5CFF` | Tor, signet |

### Shape System
- **Panel**: 24dp rounded corners (bubble)
- **Button**: 50% rounded (pill)
- **Chip**: 50% rounded (pill)
- **Field**: 16dp rounded
- **BubbleButton**: 28dp rounded

## Modifying

| What | Where |
|------|-------|
| Colours | `ui/theme/Color.kt` |
| Glow/scanlines/grid | `ui/theme/Effects.kt` |
| Shapes | `ui/theme/Shape.kt` |
| Typography | `ui/theme/Type.kt` |
| Theme composition | `ui/theme/Theme.kt` |
| Passcode screen | `ui/screens/security/AppLockScreen.kt` |
| Buttons/controls | `ui/components/Controls.kt` |
| Panels | `ui/components/Panels.kt` |
| Navigation chrome | `ui/components/Chrome.kt` |
| Prune options | `core/prefs/SettingsStore.kt` PRUNE_OPTIONS_MB |
| Sync year display | `ui/screens/node/NodeControlScreen.kt` blockYear() |
