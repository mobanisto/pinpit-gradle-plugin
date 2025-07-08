#!/bin/bash

set -e

sudo dpkg --add-architecture i386
sudo mkdir -pm755 /etc/apt/keyrings
sudo wget -O /etc/apt/keyrings/winehq-archive.key https://dl.winehq.org/wine-builds/winehq.key
sudo wget -NP /etc/apt/sources.list.d/ https://dl.winehq.org/wine-builds/ubuntu/dists/noble/winehq-noble.sources
sudo apt update
sudo apt install winehq-stable

wget https://dl.winehq.org/wine/wine-mono/10.0.0/wine-mono-10.0.0-x86.msi
wine msiexec /i wine-mono-10.0.0-x86.msi
rm wine-mono-10.0.0-x86.msi
