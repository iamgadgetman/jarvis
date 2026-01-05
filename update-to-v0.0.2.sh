#!/bin/bash
# Quick Update Script for Jarvis v0.0.2
# This script helps you update your GitHub repository quickly

set -e  # Exit on error

echo "======================================"
echo "Jarvis v0.0.2 GitHub Update Script"
echo "======================================"
echo ""

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Check if we're in a git repository
if [ ! -d ".git" ]; then
    echo -e "${RED}Error: Not in a git repository!${NC}"
    echo "Please run this script from your jarvis repository directory."
    exit 1
fi

echo -e "${YELLOW}Step 1: Checking repository status...${NC}"
git status

echo ""
echo -e "${YELLOW}Step 2: Pulling latest changes...${NC}"
git pull origin main || {
    echo -e "${RED}Warning: Could not pull. You may have uncommitted changes.${NC}"
    echo "Do you want to continue anyway? (y/n)"
    read -r response
    if [ "$response" != "y" ]; then
        exit 1
    fi
}

echo ""
echo -e "${YELLOW}Step 3: Building project...${NC}"
mvn clean package || {
    echo -e "${RED}Build failed! Please fix compilation errors.${NC}"
    exit 1
}

# Check if JAR was created
if [ ! -f "target/jarvis-0.0.2.jar" ]; then
    echo -e "${RED}Error: jarvis-0.0.2.jar was not created!${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Build successful! Created target/jarvis-0.0.2.jar${NC}"

echo ""
echo -e "${YELLOW}Step 4: Adding changes...${NC}"
git add .

echo ""
echo -e "${YELLOW}Step 5: Committing changes...${NC}"
git commit -m "Update to v0.0.2

Major Changes:
- Fixed scaffolding system (removed buggy implementation)
- Reversed ore mining priority to mine valuable ores first
- Added silk touch for deepslate emerald ore
- Fixed quest item collection tracking
- Added schematic building system
- Added ask command for AI Q&A
- Java 17 compatibility fixes
- Added GitHub project files (LICENSE, CHANGELOG, CONTRIBUTING)

See CHANGELOG.md for full details." || {
    echo -e "${YELLOW}Note: No changes to commit (maybe already committed?)${NC}"
}

echo ""
echo -e "${YELLOW}Step 6: Creating version tag...${NC}"
# Check if tag already exists
if git rev-parse v0.0.2 >/dev/null 2>&1; then
    echo -e "${YELLOW}Tag v0.0.2 already exists. Do you want to delete and recreate it? (y/n)${NC}"
    read -r response
    if [ "$response" = "y" ]; then
        git tag -d v0.0.2
        git push origin :refs/tags/v0.0.2 2>/dev/null || true
        echo "Deleted old tag"
    fi
fi

git tag -a v0.0.2 -m "Release v0.0.2 - Fixed Mining & Better Priorities

Key Features:
- Removed buggy scaffolding system
- Correct ore mining priority (valuable ores first)
- Silk touch for deepslate emerald ore
- Schematic building system
- Ask command for AI Q&A
- Complete quest system fixes

See v0.0.2_RELEASE_NOTES.md for full details."

echo -e "${GREEN}✓ Tag v0.0.2 created${NC}"

echo ""
echo -e "${YELLOW}Step 7: Pushing to GitHub...${NC}"
echo "Ready to push to https://github.com/iamgadgetman/jarvis"
echo "Continue? (y/n)"
read -r response

if [ "$response" != "y" ]; then
    echo "Push cancelled. You can push manually with:"
    echo "  git push origin main"
    echo "  git push origin v0.0.2"
    exit 0
fi

git push origin main
git push origin v0.0.2

echo ""
echo -e "${GREEN}======================================"
echo "✓ Successfully updated to v0.0.2!"
echo "======================================${NC}"
echo ""
echo "Next steps:"
echo "1. Go to: https://github.com/iamgadgetman/jarvis/releases/new"
echo "2. Select tag: v0.0.2"
echo "3. Title: v0.0.2 - Fixed Mining & Better Priorities"
echo "4. Upload: target/jarvis-0.0.2.jar"
echo "5. Description: Copy from v0.0.2_RELEASE_NOTES.md"
echo "6. Click 'Publish release'"
echo ""
echo -e "${GREEN}JAR file location: $(pwd)/target/jarvis-0.0.2.jar${NC}"
echo ""
echo "Done! 🎉"
