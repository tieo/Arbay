# Arbay

Track prices across multiple platforms (eBay, Kleinanzeigen, Amazon, etc.) and find arbitrage opportunities. Get alerts when prices drop or new listings appear.

## What it does

- **Track products** across eBay, Kleinanzeigen, Amazon, and more
- **Browse a catalog** of ~150 popular products or create custom searches
- **Price alerts** when listings drop below your threshold
- **Arbitrage detection** to spot price differences between platforms
- **Listings view** with platform filters, condition tags, and location info

## How it works

The app connects to a backend server that periodically crawls supported platforms. You pick what to track, and the server watches for new listings and price changes.

## Project structure

- `composeApp/` - Android app (Compose Multiplatform + Material 3)
- `server/` - Ktor backend with REST API
- `shared/` - Shared models and config (Kotlin Multiplatform)

## Building

```sh
# Android app
./gradlew :composeApp:assembleDebug

# Server
./gradlew :server:run
```

## Status

Early development. Android is the main target right now.
