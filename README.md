# 📸 Camera2 Flash - Kotlin Example
A sample implementation of a camera flash procedure using the **Camera2 API** and Kotlin's **CompletableDeferred**.

## Overview

This project demonstrates how to implement a flash control flow with the Android Camera2 API in Kotlin, using `CompletableDeferred` to handle the asynchronous nature of camera state callbacks in a clean, coroutine-friendly way.

Since Kotlin examples for Camera2 are surprisingly rare, this repo aims to fill that gap and give you a solid starting point.

## ⚠️ Disclaimer

This code is intended as a **reference and learning resource**, not a production-ready solution. It may lack error handling, edge case coverage, or optimizations required for a real-world app.

Use it as **inspiration** for your own implementation, not as a drop-in solution.

## What's covered

- Triggering and managing flash via the Camera2 API
- Using `CompletableDeferred` to await camera capture results
- Coroutine-based async camera state handling

## Getting started

1. Clone the repo
2. Open in Android Studio
3. Run on a physical device (camera features don't work on emulator)

## Requirements

- Android SDK 35+
- Kotlin 2.0.21+ (might also work with earlier versions but wasn't tested)
- A device with a flash unit

## License

This project is provided as-is for educational purposes. Feel free to use, modify, and adapt it for your own projects.
