package dev.thaakeno.proroot.runtime

data class CommandResult(
    val exitCode: Int,
    val output: String,
) {
    val successful: Boolean get() = exitCode == 0
}
