package dev.thaakeno.proroot.install

data class ProvisioningStage(
    val progress: Double,
    val message: String,
    val etaSeconds: Long? = null,
    val stageProgress: Double? = null,
    val stageDetail: String? = null,
    val stageDownloadedBytes: Long = 0,
    val stageTotalBytes: Long = 0,
    val stageSpeedBytesPerSecond: Long = 0,
    val completedItems: Int = 0,
    val totalItems: Int = 0,
)
