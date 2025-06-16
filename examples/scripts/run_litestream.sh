#!/bin/bash
# Example script to run Litestream replication.
# Ensure you have litestream.yml configured in the specified path or current directory.

# Path to your litestream.yml configuration file
# Update this path if your litestream.yml is located elsewhere.
LITESTREAM_CONFIG_PATH="./litestream.yml"
# Or, for example: LITESTREAM_CONFIG_PATH="/etc/litestream.yml"
# Or, for a project specific config: LITESTREAM_CONFIG_PATH="./config/litestream.yml"

if [ ! -f "$LITESTREAM_CONFIG_PATH" ]; then
    echo "Error: Litestream config file not found at $LITESTREAM_CONFIG_PATH"
    echo "Please create or update the LITESTREAM_CONFIG_PATH variable in this script."
    exit 1
fi

echo "Starting Litestream replication using config: $LITESTREAM_CONFIG_PATH"
litestream replicate -config "$LITESTREAM_CONFIG_PATH"
