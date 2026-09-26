# Contributing to OFT Receiver

Thanks for your interest in contributing! Here's how to get started.

## Getting Started

1. **Fork** the repository
2. **Clone** your fork locally
3. Create a **branch** for your change: `git checkout -b feature/my-change`
4. Make your changes
5. **Test** — run `./gradlew test` and verify on a real device if touching UI
6. **Commit** with a clear message (see below)
7. **Push** and open a Pull Request

## Commit Messages

Use concise, descriptive commit messages:

```
feat: add dark mode toggle
fix: correct CRC validation for empty chunks
docs: update protocol specification
refactor: extract QR frame parsing into separate class
```

Prefixes: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `ci`.

## Code Style

- Follow standard [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable and function names
- Keep functions focused — one function, one job
- Add comments for non-obvious logic, especially in protocol parsing

## What to Work On

- Check [open issues](https://github.com/Aayush1008/OFT-Receiver/issues) for bugs and feature requests
- Issues labeled `good first issue` are great starting points
- If you want to add something big, open an issue first to discuss the approach

## Reporting Bugs

Open an issue with:
- Device model and Android version
- Steps to reproduce
- Expected vs. actual behavior
- Logcat output if relevant

## Pull Request Guidelines

- Keep PRs focused — one feature or fix per PR
- Update the README if your change affects usage
- Make sure CI passes (tests + build)
- Add a description of what changed and why

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
