@echo off
REM Example script to run Litestream replication on Windows.
REM Ensure you have litestream.yml configured in the specified path or current directory.

REM Path to your litestream.yml configuration file
REM Update this path if your litestream.yml is located elsewhere.
SET "LITESTREAM_CONFIG_PATH=.\litestream.yml"
REM Or, for example: SET "LITESTREAM_CONFIG_PATH=C:\etc\litestream.yml"
REM Or, for a project specific config: SET "LITESTREAM_CONFIG_PATH=.\config\litestream.yml"

IF NOT EXIST "%LITESTREAM_CONFIG_PATH%" (
    echo Error: Litestream config file not found at %LITESTREAM_CONFIG_PATH%
    echo Please create or update the LITESTREAM_CONFIG_PATH variable in this script.
    exit /b 1
)

echo Starting Litestream replication using config: %LITESTREAM_CONFIG_PATH%
litestream replicate -config "%LITESTREAM_CONFIG_PATH%"
