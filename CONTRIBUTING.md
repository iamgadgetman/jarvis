# Contributing to Jarvis Minecraft AI Companion

Thank you for your interest in contributing! This document provides guidelines for contributing to the project.

## How to Contribute

### Reporting Bugs

If you find a bug, please create an issue with:
- Clear title and description
- Steps to reproduce
- Expected vs actual behavior
- Your environment (server version, Java version, plugin version)
- Relevant logs or error messages

### Suggesting Features

Feature requests are welcome! Please include:
- Clear description of the feature
- Why it would be useful
- Examples of how it would work
- Any relevant mockups or diagrams

### Pull Requests

1. **Fork the repository**
2. **Create a feature branch**: `git checkout -b feature/your-feature-name`
3. **Make your changes**
4. **Test thoroughly**
5. **Commit with clear messages**: `git commit -m "Add: feature description"`
6. **Push to your fork**: `git push origin feature/your-feature-name`
7. **Create a Pull Request**

## Development Setup

### Prerequisites
- Java 17 JDK
- Maven 3.9+
- Git
- A test Minecraft server (Purpur/Spigot 1.21+)
- Citizens plugin
- WorldEdit plugin

### Building

```bash
# Clone your fork
git clone https://github.com/yourusername/jarvis-minecraft.git
cd jarvis-minecraft

# Build
mvn clean package

# Output will be in target/jarvis-0.0.2.jar
```

### Testing

1. Copy the JAR to your test server's `plugins/` folder
2. Configure API keys in `plugins/Jarvis/config.yml`
3. Restart the server
4. Test your changes thoroughly

## Code Style

### Java
- Use 4 spaces for indentation
- Follow standard Java naming conventions
- Add JavaDoc comments for public methods
- Keep methods focused and under 50 lines when possible
- Use meaningful variable names

### Example
```java
/**
 * Finds the best ore to mine based on priority and distance
 * @param center The center location to search from
 * @return The best ore block, or null if none found
 */
private Block findBestOre(Location center) {
    // Implementation
}
```

## Commit Message Guidelines

Use clear, descriptive commit messages:

- `Add: new feature description`
- `Fix: bug description`
- `Update: what was updated`
- `Remove: what was removed`
- `Refactor: what was refactored`
- `Docs: documentation changes`

## Testing Checklist

Before submitting a PR, ensure:
- [ ] Code compiles without errors
- [ ] No new warnings introduced
- [ ] Feature works as expected
- [ ] No existing features broken
- [ ] Tested on a live server
- [ ] Documentation updated if needed
- [ ] CHANGELOG.md updated

## Areas for Contribution

### High Priority
- Quest system enhancements
- Additional AI provider integrations
- Performance optimizations
- Bug fixes

### Medium Priority
- New NPC behaviors
- Additional schematic features
- UI improvements
- Configuration options

### Low Priority
- Documentation improvements
- Code cleanup
- Additional examples

## Questions?

- Open an issue for discussion
- Check existing issues/PRs first
- Be patient and respectful

## License

By contributing, you agree that your contributions will be licensed under the MIT License.

---

**Thank you for helping make Jarvis better! 🎉**
