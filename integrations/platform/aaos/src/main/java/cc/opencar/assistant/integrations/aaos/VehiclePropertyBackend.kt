package cc.opencar.assistant.integrations.aaos

import kotlinx.coroutines.flow.Flow

/**
 * Property read/write facade for AAOS vehicle properties.
 * Integrations pick a transport (CarPropertyManager, gRPC, …).
 *
 * Push transports implement [observe]; poll-only transports leave it null and
 * the [cc.opencar.assistant.api.VehicleSession] runs its own loop.
 */
interface VehiclePropertyBackend : AutoCloseable {
    val mode: PropertyAccessMode
    val available: Boolean

    fun read(propId: Int, areaId: Int): Any?
    fun readDetailed(propId: Int, areaId: Int): DetailedRead
    fun writeInt(propId: Int, areaId: Int, value: Int): Boolean
    fun writeFloat(propId: Int, areaId: Int, value: Float): Boolean
    fun writeBoolean(propId: Int, areaId: Int, value: Boolean): Boolean

    /** Full int32 vector ([read] returns the first element only); null when unsupported. */
    fun readIntVector(propId: Int, areaId: Int): List<Int>? = null

    fun writeIntVector(propId: Int, areaId: Int, values: List<Int>): Boolean = false

    /**
     * Hot stream of property changes when the transport can push.
     * @param propIds optional interest set (CarProperty); null/empty = all (gRPC) or unavailable (CarProperty).
     * @return null when the session must poll via [read].
     */
    fun observe(propIds: IntArray? = null): Flow<PropertyUpdate>? = null

    sealed class DetailedRead {
        data class Ok(val value: Any?) : DetailedRead()
        data class Denied(val permission: String?, val message: String?) : DetailedRead()
        data class Failed(val message: String?) : DetailedRead()
        data object Empty : DetailedRead()
        data object Unavailable : DetailedRead()
    }
}

data class PropertyUpdate(
    val propId: Int,
    val areaId: Int,
    val value: Any?,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * How a session talks to VHAL.
 *
 * - [CAR_PROPERTY]: [android.car.CarPropertyManager]
 * - [GRPC]: platform-specific user-space transport (e.g. Antora VenusVehicleServer)
 */
enum class PropertyAccessMode(val wireName: String) {
    CAR_PROPERTY("car_property"),
    GRPC("grpc"),
}
