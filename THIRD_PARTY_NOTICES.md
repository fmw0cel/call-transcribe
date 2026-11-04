# Third-party notices

This project is MIT licensed (see `LICENSE`). It bundles or depends on the
following third-party software and model weights.

## whisper.cpp

- Vendored at `whisper.cpp/` (v1.9.5), with examples/tests/models removed to keep
  the repository small.
- License: MIT — Copyright (c) 2022-2024 Georgi Gerganov
- Source: https://github.com/ggml-org/whisper.cpp
- The original license text is kept in `whisper.cpp/LICENSE`.

## ggml

- Included inside `whisper.cpp/ggml/`.
- License: MIT — Copyright (c) 2023-2024 Georgi Gerganov and contributors
- Source: https://github.com/ggml-org/ggml

## OpenAI Whisper model weights

- Not bundled with the app. The app downloads `ggml-*.bin` files at runtime from
  Hugging Face (`ggerganov/whisper.cpp`), at the user's request.
- License: MIT — Copyright (c) 2022 OpenAI
- Source: https://github.com/openai/whisper

## AndroidX / Jetpack Compose

- Copyright The Android Open Source Project
- License: Apache License 2.0
- https://developer.android.com/jetpack/androidx

## Kotlin, Kotlin Coroutines

- Copyright JetBrains s.r.o.
- License: Apache License 2.0
- https://kotlinlang.org

## Material Icons

- Copyright Google LLC
- License: Apache License 2.0
- https://fonts.google.com/icons

## App icon

- Original artwork generated for this project; see `design/`.
- Released under the same MIT license as the rest of the project.
