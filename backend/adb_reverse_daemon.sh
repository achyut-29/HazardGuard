#!/usr/bin/env bash
# Auto-maintain ADB reverse and wireless ADB connection to phone
while true; do
    # If phone is not connected via USB or Wi-Fi, attempt wireless adb connect
    if ! adb devices | grep -v "List" | grep -w "device" | grep -q ":5555"; then
        adb connect 10.1.25.110:5555 2>/dev/null
    fi
    # Ensure port reverse is active on all connected devices (USB and Wi-Fi)
    for dev in $(adb devices | grep -w "device" | awk '{print $1}'); do
        if ! adb -s "$dev" reverse --list 2>/dev/null | grep -q "tcp:5678"; then
            adb -s "$dev" reverse tcp:5678 tcp:5678 2>/dev/null
        fi
    done
    sleep 3
done
