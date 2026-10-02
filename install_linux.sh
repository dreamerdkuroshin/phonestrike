#!/usr/bin/env bash

# PocketstrikeAI Installer Script for Linux (Debian / Ubuntu / Kali / Mint / Elementary)
# Designed to set up Linux system dependencies and Python environments.

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
echo -e "       🐧 ${BLUE}Pocket${GREEN}Strike-AI ${NC}— ${BLUE}Linux Initializer (apt)${NC} 🐧"
echo -e "${GREEN}──────────────────────────────────────────────────────────────────────────${NC}"
echo -e "🚀 Starting Linux high-performance system deployment..."
echo -e "💻 Target OS: Debian / Ubuntu / Kali Linux / Linux Mint"
echo -e "${GREEN}──────────────────────────────────────────────────────────────────────────${NC}\n"

# 1. Check for apt package manager
if [ ! -x "$(command -v apt-get)" ] && [ ! -x "$(command -v apt)" ]; then
    echo -e "${RED}Error: This installer is designed for Debian/Ubuntu-based Linux distros using 'apt'.${NC}"
    exit 1
fi

# 2. Update package lists
echo -e "${BLUE}⚡ [1/4] Updating Linux package repository mirrors...${NC}"
sudo apt-get update -y || echo -e "${YELLOW}Warning: apt update encountered non-fatal mirror errors, proceeding...${NC}"

# 3. Install required system audit and security packages
echo -e "\n${BLUE}⚡ [2/4] Deploying Linux security toolchain & dependencies...${NC}"
sudo apt-get install -y \
    python3 \
    python3-pip \
    python3-venv \
    git \
    curl \
    nmap \
    dnsutils \
    net-tools \
    iproute2 \
    traceroute \
    libnotify-bin \
    speech-dispatcher \
    espeak-ng \
    ca-certificates || {
    echo -e "${RED}Error: Failed to install required system packages. Please check internet connection.${NC}"
    exit 1
}

# Create local workspace directory
echo -e "\n${BLUE}📁 Initializing workspace directory (~/PocketStrike-AI/workspace)...${NC}"
mkdir -p ~/PocketStrike-AI/workspace || mkdir -p ./workspace

# 4. Install Python requirements
echo -e "\n${BLUE}⚡ [3/4] Installing Python dependency layers...${NC}"
pip3 install --break-system-packages flask requests SpeechRecognition opencv-python 2>/dev/null || pip install flask requests SpeechRecognition opencv-python

# 5. Set execution permissions
echo -e "\n${BLUE}⚡ [4/4] Setting execution system permissions...${NC}"
chmod +x launch_linux.sh 2>/dev/null || true
chmod +x launch.sh 2>/dev/null || true
chmod +x setup.py 2>/dev/null || true
chmod +x install_linux.sh 2>/dev/null || true

echo -e "\n${GREEN}──────────────────────────────────────────────────────────────────────────${NC}"
echo -e "       ✨ ${BLUE}Pocket${GREEN}Strike-AI ${NC}— ${GREEN}Linux Deployment Complete!${NC} ✨"
echo -e "${GREEN}──────────────────────────────────────────────────────────────────────────${NC}"
echo -e "You can now initialize the setup wizard and launch the AI on Linux."
echo -e "To launch, run: ${YELLOW}./launch_linux.sh${NC} or ${YELLOW}python3 server.py${NC}"
echo -e "${GREEN}──────────────────────────────────────────────────────────────────────────${NC}"
