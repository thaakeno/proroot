package dev.thaakeno.proroot.runtime

enum class RuntimePhase {
    missing,
    downloading,
    extracting,
    provisioning,
    ready,
    starting,
    running,
    stopping,
    failed,
}
