#!/usr/bin/env bash

# PocketstrikeAI Installer Script for macOS (Homebrew)
# Designed to set up macOS system dependencies and Python environments.

# Exit immediately if a command exits with a non-zero status
set -e

# Define colors for output
BLUE='\033[38;5;39m' # Vibrant Cyber Blue
GREEN='\033[38;5;46m' # Bright Green
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

clear
echo -e "${BLUE}  _____            _        _    _____ _        _ _     ${NC}"
echo -e "${BLUE} |  __ \\          | |      | |  / ____| |      (_) |    ${NC}"
echo -e "${BLUE} | |__) |__   ___ | | _____| |_| (___ | |_ _ __ _| | ___ ${NC}"
echo -e "${BLUE} |  ___/ _ \\ / __|| |/ / _ \\ __|\\___ \\| __| '__| | |/ / ${NC}"
echo -e "${BLUE} | |  | (_) | (__ |   <  __/ |_ ____) | |_| |  | |   <  ${NC}"
echo -e "${BLUE} |_|   \\___/ \\___||_|\\_\\___|\\__|_____/ \\__|_|  |_|_|\\_\\${NC}"
echo -e "       🍎 ${BLUE}Pocket${GREEN}Strike-AI ${NC}— ${BLUE}macOS Initializer (Homebrew)${NC} 🍎"
echo -e "${GREEN}──────────────────────────────────────────────────────────────────────────${NC}"
echo -e "🚀 Starting macOS high-performance system deployment..."
echo -e "💻 Target OS: macOS (Apple Silicon M1/M2/M3/M4 & Intel)"
echo -e "${GREEN}──────────────────────────────────────────────────────────────────────────${NC}\n"

# 1. Check for Homebrew
if [ ! -x "$(command -v brew)" ]; then
    echo -e "${YELLOW}Homebrew package manager not found. Installing Homebrew...${NC}"
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)" || {
        echo -e "${RED}Error: Failed to install Homebrew automatically. Please install Homebrew manually from https://brew.sh${NC}"
        exit 1
    }
fi

# 2. Update Homebrew formulae
echo -e "${BLUE}⚡ [1/4] Updating Homebrew repository mirrors...${NC}"
brew update || echo -e "${YELLOW}Warning: brew update encountered minor warnings, proceeding...${NC}"

# 3. Install required CLI tools via brew
echo -e "\n${BLUE}⚡ [2/4] Deploying macOS security toolchain & dependencies...${NC}"
brew install python3 git nmap curl net-tools traceroute || echo -e "${YELLOW}Warning: Some brew packages were already installed.${NC}"

# Create workspace directory
echo -e "\n${BLUE}📁 Initializing macOS workspace directory (~/PocketStrike-AI/workspace)...${NC}"
mkdir -p ~/PocketStrike-AI/workspace || mkdir -p ./workspace

# 4. Install Python dependencies
echo -e "\n${BLUE}⚡ [3/4] Installing Python dependency layers...${NC}"
pip3 install flask requests SpeechRecognition opencv-python 2>/dev/null || pip install flask requests SpeechRecognition opencv-python

# 5. Set execution permissions
echo -e "\n${BLUE}⚡ [4/4] Setting execution system permissions...${NC}"
chmod +x launch_mac.sh 2>/dev/null || true
chmod +x launch.sh 2>/dev/null || true
chmod +x setup.py 2>/dev/null || true
chmod +x install_mac.sh 2>/dev/null || true

echo -e "\n${GREEN}──────────────────────────────────────────────────────────────────────────${NC}"
echo -e "       ✨ ${BLUE}Pocket${GREEN}Strike-AI ${NC}— ${GREEN}macOS Deployment Complete!${NC} ✨"
echo -e "${GREEN}──────────────────────────────────────────────────────────────────────────${NC}"
echo -e "You can now initialize the setup wizard and launch the AI on macOS."
echo -e "To launch, run: ${YELLOW}./launch_mac.sh${NC} or ${YELLOW}python3 server.py${NC}"
echo -e "${GREEN}──────────────────────────────────────────────────────────────────────────${NC}"
