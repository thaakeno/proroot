#!/usr/bin/python3
import os
import time

import dbus
import dbus.mainloop.glib
import dbus.service
from gi.repository import GLib

from host_dbus import PropertyObject, read_info, write_ready

READY_FILE = "/run/proroot-upower.ready"
UPOWER_IFACE = "org.freedesktop.UPower"
DEVICE_IFACE = "org.freedesktop.UPower.Device"
DISPLAY_PATH = "/org/freedesktop/UPower/devices/DisplayDevice"


def android_state(info):
    try:
        status = int(info.get("battery_status", "1"))
    except ValueError:
        status = 1
    return {
        2: 1,
        3: 2,
        4: 0,
        5: 4,
    }.get(status, 0)


def percentage(info):
    try:
        value = float(info.get("battery_percent", "-1"))
    except ValueError:
        return 0.0
    if value < 0:
        return 0.0
    return max(0.0, min(100.0, value))


class DisplayDevice(PropertyObject):
    interface = DEVICE_IFACE

    def __init__(self, bus_name):
        super().__init__(bus_name, DISPLAY_PATH)
        self._last = {}

    def properties(self):
        info = read_info()
        return {
            "NativePath": dbus.String("android-battery"),
            "Vendor": dbus.String(info.get("soc_manufacturer", "Android")),
            "Model": dbus.String(info.get("model", "Android device")),
            "Serial": dbus.String(""),
            "UpdateTime": dbus.UInt64(int(time.time())),
            "Type": dbus.UInt32(2),
            "PowerSupply": dbus.Boolean(True),
            "HasHistory": dbus.Boolean(False),
            "HasStatistics": dbus.Boolean(False),
            "Online": dbus.Boolean(False),
            "Energy": dbus.Double(0.0),
            "EnergyEmpty": dbus.Double(0.0),
            "EnergyFull": dbus.Double(0.0),
            "EnergyFullDesign": dbus.Double(0.0),
            "EnergyRate": dbus.Double(0.0),
            "Voltage": dbus.Double(0.0),
            "ChargeCycles": dbus.Int32(-1),
            "Luminosity": dbus.Double(0.0),
            "TimeToEmpty": dbus.Int64(0),
            "TimeToFull": dbus.Int64(0),
            "Percentage": dbus.Double(percentage(info)),
            "Temperature": dbus.Double(0.0),
            "IsPresent": dbus.Boolean(True),
            "State": dbus.UInt32(android_state(info)),
            "IsRechargeable": dbus.Boolean(True),
            "Capacity": dbus.Double(100.0),
            "Technology": dbus.UInt32(0),
            "WarningLevel": dbus.UInt32(1),
            "BatteryLevel": dbus.UInt32(1),
            "IconName": dbus.String("battery"),
        }

    def refresh(self):
        current = self.properties()
        comparable = {key: value for key, value in current.items() if key != "UpdateTime"}
        if comparable != self._last:
            self._last = comparable
            self.PropertiesChanged(self.interface, current, [])


class UPower(PropertyObject):
    interface = UPOWER_IFACE

    def __init__(self, bus_name, display):
        super().__init__(bus_name, "/org/freedesktop/UPower")
        self.display = display
        self._on_battery = None

    def properties(self):
        state = android_state(read_info())
        return {
            "DaemonVersion": dbus.String("proroot-android-bridge"),
            "OnBattery": dbus.Boolean(state == 2),
            "LidIsClosed": dbus.Boolean(False),
            "LidIsPresent": dbus.Boolean(False),
        }

    @dbus.service.method(UPOWER_IFACE, in_signature="", out_signature="ao")
    def EnumerateDevices(self):
        return [dbus.ObjectPath(DISPLAY_PATH)]

    @dbus.service.method(UPOWER_IFACE, in_signature="", out_signature="o")
    def GetDisplayDevice(self):
        return dbus.ObjectPath(DISPLAY_PATH)

    @dbus.service.method(UPOWER_IFACE, in_signature="", out_signature="s")
    def GetCriticalAction(self):
        return "PowerOff"

    @dbus.service.signal(UPOWER_IFACE, signature="o")
    def DeviceAdded(self, device):
        pass

    @dbus.service.signal(UPOWER_IFACE, signature="o")
    def DeviceRemoved(self, device):
        pass

    def refresh(self):
        current = bool(self.properties()["OnBattery"])
        if self._on_battery is None or current != self._on_battery:
            self._on_battery = current
            self.PropertiesChanged(
                self.interface,
                {"OnBattery": dbus.Boolean(current)},
                [],
            )
        self.display.refresh()
        return True


def main():
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    name = dbus.service.BusName(
        UPOWER_IFACE,
        bus=bus,
        allow_replacement=True,
        replace_existing=True,
        do_not_queue=True,
    )
    display = DisplayDevice(name)
    upower = UPower(name, display)
    write_ready(READY_FILE)

    upower.refresh()
    GLib.timeout_add_seconds(1, upower.refresh)
    try:
        GLib.MainLoop().run()
    finally:
        try:
            os.unlink(READY_FILE)
        except OSError:
            pass


if __name__ == "__main__":
    main()
