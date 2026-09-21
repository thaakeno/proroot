#!/usr/bin/python3
import os

import dbus
import dbus.service

HOST_INFO = "/run/proroot-host-info"
PROPS_IFACE = "org.freedesktop.DBus.Properties"


def read_info():
    result = {}
    try:
        with open(HOST_INFO, "r", encoding="utf-8") as stream:
            for raw in stream:
                key, sep, value = raw.rstrip("\n").partition("=")
                if sep:
                    result[key] = value
    except OSError:
        pass
    return result


def host_uid():
    try:
        return int(read_info().get("android_uid", str(os.getuid())))
    except (TypeError, ValueError):
        return os.getuid()


def write_ready(path):
    with open(path, "w", encoding="utf-8") as stream:
        stream.write(str(os.getpid()))


class PropertyObject(dbus.service.Object):
    interface = ""

    def properties(self):
        return {}

    @dbus.service.method(PROPS_IFACE, in_signature="ss", out_signature="v")
    def Get(self, interface_name, property_name):
        if interface_name != self.interface:
            raise dbus.exceptions.DBusException(
                "org.freedesktop.DBus.Error.InvalidArgs",
                "Unknown interface",
            )
        props = self.properties()
        if property_name not in props:
            raise dbus.exceptions.DBusException(
                "org.freedesktop.DBus.Error.InvalidArgs",
                "Unknown property",
            )
        return props[property_name]

    @dbus.service.method(PROPS_IFACE, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface_name):
        if interface_name != self.interface:
            return {}
        return self.properties()

    @dbus.service.method(PROPS_IFACE, in_signature="ssv", out_signature="")
    def Set(self, interface_name, property_name, value):
        raise dbus.exceptions.DBusException(
            "org.freedesktop.DBus.Error.PropertyReadOnly",
            "Compatibility bridge properties are read-only",
        )

    @dbus.service.signal(PROPS_IFACE, signature="sa{sv}as")
    def PropertiesChanged(self, interface_name, changed, invalidated):
        pass
