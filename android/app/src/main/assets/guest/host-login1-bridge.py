#!/usr/bin/python3
import os
import time

import dbus
import dbus.mainloop.glib
import dbus.service
from gi.repository import GLib

from host_dbus import PropertyObject, host_uid, write_ready

READY_FILE = "/run/proroot-login1.ready"
MANAGER_IFACE = "org.freedesktop.login1.Manager"
SESSION_IFACE = "org.freedesktop.login1.Session"
USER_IFACE = "org.freedesktop.login1.User"
SEAT_IFACE = "org.freedesktop.login1.Seat"
SESSION_ID = "c1"
SESSION_PATH = "/org/freedesktop/login1/session/c1"
SEAT_ID = "seat0"
SEAT_PATH = "/org/freedesktop/login1/seat/seat0"


def user_path(uid):
    return f"/org/freedesktop/login1/user/_{uid}"


def user_struct(uid):
    return dbus.Struct(
        (dbus.UInt32(uid), dbus.ObjectPath(user_path(uid))),
        signature="uo",
    )


def session_struct():
    return dbus.Struct(
        (dbus.String(SESSION_ID), dbus.ObjectPath(SESSION_PATH)),
        signature="so",
    )


class Manager(PropertyObject):
    interface = MANAGER_IFACE

    def __init__(self, bus_name, uid):
        super().__init__(bus_name, "/org/freedesktop/login1")
        self.uid = uid

    def properties(self):
        return {
            "PreparingForShutdown": dbus.Boolean(False),
            "PreparingForSleep": dbus.Boolean(False),
            "IdleHint": dbus.Boolean(False),
            "IdleSinceHint": dbus.UInt64(0),
            "IdleSinceHintMonotonic": dbus.UInt64(0),
            "BlockInhibited": dbus.String(""),
            "DelayInhibited": dbus.String(""),
        }

    @dbus.service.method(MANAGER_IFACE, in_signature="s", out_signature="o")
    def GetSession(self, session_id):
        if session_id != SESSION_ID:
            raise dbus.exceptions.DBusException(
                "org.freedesktop.login1.NoSuchSession",
                "Unknown session",
            )
        return dbus.ObjectPath(SESSION_PATH)

    @dbus.service.method(MANAGER_IFACE, in_signature="u", out_signature="o")
    def GetSessionByPID(self, pid):
        return dbus.ObjectPath(SESSION_PATH)

    @dbus.service.method(MANAGER_IFACE, in_signature="u", out_signature="o")
    def GetUser(self, uid):
        return dbus.ObjectPath(user_path(uid))

    @dbus.service.method(MANAGER_IFACE, in_signature="u", out_signature="o")
    def GetUserByPID(self, pid):
        return dbus.ObjectPath(user_path(self.uid))

    @dbus.service.method(MANAGER_IFACE, in_signature="s", out_signature="o")
    def GetSeat(self, seat_id):
        if seat_id != SEAT_ID:
            raise dbus.exceptions.DBusException(
                "org.freedesktop.login1.NoSuchSeat",
                "Unknown seat",
            )
        return dbus.ObjectPath(SEAT_PATH)

    @dbus.service.method(MANAGER_IFACE, in_signature="", out_signature="a(susso)")
    def ListSessions(self):
        return [
            (
                dbus.String(SESSION_ID),
                dbus.UInt32(self.uid),
                dbus.String("linux"),
                dbus.String(SEAT_ID),
                dbus.ObjectPath(SESSION_PATH),
            )
        ]

    @dbus.service.method(MANAGER_IFACE, in_signature="", out_signature="a(uso)")
    def ListUsers(self):
        return [
            (
                dbus.UInt32(self.uid),
                dbus.String("linux"),
                dbus.ObjectPath(user_path(self.uid)),
            )
        ]

    @dbus.service.method(MANAGER_IFACE, in_signature="", out_signature="a(so)")
    def ListSeats(self):
        return [(dbus.String(SEAT_ID), dbus.ObjectPath(SEAT_PATH))]

    @dbus.service.method(MANAGER_IFACE, in_signature="", out_signature="a(ssssuu)")
    def ListInhibitors(self):
        return []

    @dbus.service.method(MANAGER_IFACE, in_signature="ssss", out_signature="h")
    def Inhibit(self, what, who, why, mode):
        fd = os.open("/dev/null", os.O_RDONLY)
        try:
            return dbus.types.UnixFd(fd)
        finally:
            os.close(fd)

    @dbus.service.method(MANAGER_IFACE, in_signature="", out_signature="s")
    def CanPowerOff(self):
        return "na"

    @dbus.service.method(MANAGER_IFACE, in_signature="", out_signature="s")
    def CanReboot(self):
        return "na"

    @dbus.service.method(MANAGER_IFACE, in_signature="", out_signature="s")
    def CanSuspend(self):
        return "na"

    @dbus.service.method(MANAGER_IFACE, in_signature="", out_signature="s")
    def CanHibernate(self):
        return "na"

    @dbus.service.method(MANAGER_IFACE, in_signature="", out_signature="s")
    def CanHybridSleep(self):
        return "na"


class Session(PropertyObject):
    interface = SESSION_IFACE

    def __init__(self, bus_name, uid):
        super().__init__(bus_name, SESSION_PATH)
        self.uid = uid
        self.timestamp = int(time.time() * 1_000_000)
        self.monotonic = int(time.monotonic() * 1_000_000)

    def properties(self):
        return {
            "Id": dbus.String(SESSION_ID),
            "User": user_struct(self.uid),
            "Name": dbus.String("linux"),
            "Timestamp": dbus.UInt64(self.timestamp),
            "TimestampMonotonic": dbus.UInt64(self.monotonic),
            "VTNr": dbus.UInt32(0),
            "Seat": dbus.Struct(
                (dbus.String(SEAT_ID), dbus.ObjectPath(SEAT_PATH)),
                signature="so",
            ),
            "TTY": dbus.String(""),
            "Display": dbus.String(""),
            "Remote": dbus.Boolean(False),
            "RemoteHost": dbus.String(""),
            "RemoteUser": dbus.String(""),
            "Service": dbus.String("proroot"),
            "Desktop": dbus.String("KDE"),
            "Scope": dbus.String(""),
            "Leader": dbus.UInt32(os.getpid()),
            "Audit": dbus.UInt32(0),
            "Type": dbus.String("wayland"),
            "Class": dbus.String("user"),
            "Active": dbus.Boolean(True),
            "State": dbus.String("active"),
            "IdleHint": dbus.Boolean(False),
            "IdleSinceHint": dbus.UInt64(0),
            "IdleSinceHintMonotonic": dbus.UInt64(0),
        }

    @dbus.service.method(SESSION_IFACE, in_signature="", out_signature="")
    def Activate(self):
        return

    @dbus.service.method(SESSION_IFACE, in_signature="b", out_signature="")
    def SetIdleHint(self, idle):
        return


class User(PropertyObject):
    interface = USER_IFACE

    def __init__(self, bus_name, uid):
        super().__init__(bus_name, user_path(uid))
        self.uid = uid
        self.timestamp = int(time.time() * 1_000_000)
        self.monotonic = int(time.monotonic() * 1_000_000)

    def properties(self):
        runtime_path = f"/run/user/{self.uid}"
        return {
            "UID": dbus.UInt32(self.uid),
            "GID": dbus.UInt32(self.uid),
            "Name": dbus.String("linux"),
            "Timestamp": dbus.UInt64(self.timestamp),
            "TimestampMonotonic": dbus.UInt64(self.monotonic),
            "RuntimePath": dbus.String(runtime_path),
            "Service": dbus.String(""),
            "Slice": dbus.String(""),
            "Display": dbus.Struct(
                (dbus.String(SESSION_ID), dbus.ObjectPath(SESSION_PATH)),
                signature="so",
            ),
            "State": dbus.String("active"),
            "Sessions": dbus.Array([session_struct()], signature="(so)"),
            "IdleHint": dbus.Boolean(False),
            "IdleSinceHint": dbus.UInt64(0),
            "IdleSinceHintMonotonic": dbus.UInt64(0),
            "Linger": dbus.Boolean(False),
        }


class Seat(PropertyObject):
    interface = SEAT_IFACE

    def __init__(self, bus_name):
        super().__init__(bus_name, SEAT_PATH)

    def properties(self):
        return {
            "Id": dbus.String(SEAT_ID),
            "ActiveSession": session_struct(),
            "CanMultiSession": dbus.Boolean(False),
            "CanTTY": dbus.Boolean(False),
            "CanGraphical": dbus.Boolean(True),
            "Sessions": dbus.Array([session_struct()], signature="(so)"),
            "IdleHint": dbus.Boolean(False),
            "IdleSinceHint": dbus.UInt64(0),
            "IdleSinceHintMonotonic": dbus.UInt64(0),
        }


def main():
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    name = dbus.service.BusName(
        "org.freedesktop.login1",
        bus=bus,
        allow_replacement=True,
        replace_existing=True,
        do_not_queue=True,
    )
    uid = host_uid()
    manager = Manager(name, uid)
    session = Session(name, uid)
    user = User(name, uid)
    seat = Seat(name)

    write_ready(READY_FILE)
    try:
        GLib.MainLoop().run()
    finally:
        try:
            os.unlink(READY_FILE)
        except OSError:
            pass


if __name__ == "__main__":
    main()
