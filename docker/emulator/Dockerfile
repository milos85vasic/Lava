# Lava Android Emulator Container (SP-3a Phase 5+).
#
# Builds a containerized Android emulator environment for running
# Challenge Tests and provider-specific end-to-end tests.
#
# Constitutional requirement: clause 6.G — every provider MUST have
# at least one end-to-end test on a real device/emulator surface.
#
# This image expects:
#   - /dev/kvm mounted from the host (privileged mode).
#   - Host Android SDK mounted at /opt/android-sdk:ro.
#   - Environment variable AVD_NAME set to a host-created AVD.

FROM docker.io/library/eclipse-temurin:17-jdk

LABEL org.opencontainers.image.title="Lava Android Emulator"
LABEL org.opencontainers.image.description="Containerized Android emulator for Lava end-to-end tests"
LABEL org.opencontainers.image.source="https://github.com/milos85vasic/Lava"

# Install required packages
RUN apt-get update && apt-get install -y --no-install-recommends \
    qemu-kvm \
    libvirt-daemon-system \
    libvirt-clients \
    bridge-utils \
    libgl1-mesa-glx \
    libpulse0 \
    wget \
    unzip \
    socat \
    && rm -rf /var/lib/apt/lists/*

# Add the current user to the kvm group (inside container)
RUN usermod -aG kvm root

# Create android-sdk directory structure (will be mounted from host)
RUN mkdir -p /opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk

# Install adb and emulator binaries if SDK not mounted
# (Fallback for standalone use; normal path is host mount.)
RUN if [ ! -f /opt/android-sdk/emulator/emulator ]; then \
        echo "WARNING: No host Android SDK mounted. Emulator will not start."; \
    fi

# Copy entrypoint script
COPY docker/emulator/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# Expose emulator console and adb ports
EXPOSE 5554 5555

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
