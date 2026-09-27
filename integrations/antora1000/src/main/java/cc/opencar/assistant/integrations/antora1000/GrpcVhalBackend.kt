package cc.opencar.assistant.integrations.antora1000

import android.util.Log
import cc.opencar.assistant.integrations.aaos.PropertyAccessMode
import cc.opencar.assistant.integrations.aaos.PropertyUpdate
import cc.opencar.assistant.integrations.aaos.VehiclePropertyBackend
import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-space Antora VHAL access via ECARX VenusVehicleServer gRPC
 * (`vhal_proto.VehicleServer` on localhost:40004).
 *
 * [StartPropertyValuesStream] pushes into [cache] and [updates]; [read] is cache-only.
 */
class GrpcVhalBackend(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
) : VehiclePropertyBackend {
    override val mode: PropertyAccessMode = PropertyAccessMode.GRPC

    private val cache = ConcurrentHashMap<Long, VhalProto.CachedProp>()
    private val updates = MutableSharedFlow<PropertyUpdate>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "oaa-vhal-grpc-write").apply { isDaemon = true }
    }
    private val connected = AtomicBoolean(false)
    private var channel: ManagedChannel? = null
    private var setRequestObserver: StreamObserver<ByteArray>? = null
    private val streamAlive = AtomicBoolean(false)

    override val available: Boolean get() = connected.get()

    override fun observe(propIds: IntArray?): Flow<PropertyUpdate> = updates.asSharedFlow()

    fun connect(): Boolean {
        if (connected.get()) return true
        return try {
            val sessionId = UUID.randomUUID().toString()
            val clientId = "oaa_vhal_" +
                UUID.randomUUID().toString().replace("-", "").take(8)
            val headers = Metadata().apply {
                put(SESSION_KEY, sessionId)
                put(CLIENT_KEY, clientId)
            }
            val ch = OkHttpChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
                .build()
            channel = ch

            val latch = CountDownLatch(1)
            val setCall = ch.newCall(SET_PROPERTY, CallOptions.DEFAULT)
            val requestObs = ClientCalls.asyncBidiStreamingCall(
                setCall,
                object : StreamObserver<ByteArray> {
                    override fun onNext(value: ByteArray) {
                        // Empty body = status_code 0 (OK).
                    }
                    override fun onError(t: Throwable) {
                        Log.w(TAG, "SetProperty stream error: ${t.message}")
                        streamAlive.set(false)
                        latch.countDown()
                    }
                    override fun onCompleted() {
                        streamAlive.set(false)
                        latch.countDown()
                    }
                },
            )
            setRequestObserver = requestObs
            streamAlive.set(true)

            // Property value stream → cache + observe fan-out
            Thread({
                try {
                    val call = ch.newCall(START_STREAM, CallOptions.DEFAULT)
                    ClientCalls.asyncServerStreamingCall(
                        call,
                        ByteArray(0),
                        object : StreamObserver<ByteArray> {
                            override fun onNext(value: ByteArray) {
                                for (p in VhalProto.parseValueList(value)) {
                                    cache[cacheKey(p.propId, p.areaId)] = p
                                    updates.tryEmit(
                                        PropertyUpdate(
                                            propId = p.propId,
                                            areaId = p.areaId,
                                            value = p.primary(),
                                        ),
                                    )
                                }
                                latch.countDown()
                            }
                            override fun onError(t: Throwable) {
                                Log.w(TAG, "StartPropertyValuesStream: ${t.message}")
                                latch.countDown()
                            }
                            override fun onCompleted() {
                                latch.countDown()
                            }
                        },
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "stream start failed: ${t.message}")
                    latch.countDown()
                }
            }, "oaa-vhal-grpc-stream").apply { isDaemon = true }.start()

            // Snapshot push
            try {
                val call = ch.newCall(SEND_ALL, CallOptions.DEFAULT)
                ClientCalls.blockingUnaryCall(call, ByteArray(0))
            } catch (t: Throwable) {
                Log.w(TAG, "SendAllPropertyValuesToStream: ${t.message}")
            }

            // Wait briefly for first cache fill
            latch.await(3, TimeUnit.SECONDS)
            if (cache.isEmpty()) {
                Log.w(TAG, "gRPC connected but cache empty — treating as unavailable")
                closeQuietly()
                return false
            }
            connected.set(true)
            Log.i(TAG, "VenusVehicleServer ready client_id=$clientId cache=${cache.size}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "gRPC connect failed: ${t.message}")
            closeQuietly()
            false
        }
    }

    override fun read(propId: Int, areaId: Int): Any? =
        when (val d = readDetailed(propId, areaId)) {
            is VehiclePropertyBackend.DetailedRead.Ok -> d.value
            else -> null
        }

    override fun readDetailed(propId: Int, areaId: Int): VehiclePropertyBackend.DetailedRead {
        if (!connected.get()) return VehiclePropertyBackend.DetailedRead.Unavailable
        // Exact area only — do not fall back to another zone (HVAC areas differ).
        val hit = cache[cacheKey(propId, areaId)]
            ?: return VehiclePropertyBackend.DetailedRead.Empty
        val value = hit.primary() ?: return VehiclePropertyBackend.DetailedRead.Empty
        return VehiclePropertyBackend.DetailedRead.Ok(value)
    }

    override fun writeInt(propId: Int, areaId: Int, value: Int): Boolean =
        writeBytes(VhalProto.encodeSetInt(propId, areaId, value), propId, areaId) {
            cache[cacheKey(propId, areaId)] = VhalProto.CachedProp(propId, areaId, int32 = listOf(value))
            updates.tryEmit(PropertyUpdate(propId, areaId, value))
        }

    override fun writeFloat(propId: Int, areaId: Int, value: Float): Boolean =
        writeBytes(VhalProto.encodeSetFloat(propId, areaId, value), propId, areaId) {
            cache[cacheKey(propId, areaId)] = VhalProto.CachedProp(propId, areaId, float = listOf(value))
            updates.tryEmit(PropertyUpdate(propId, areaId, value))
        }

    override fun writeBoolean(propId: Int, areaId: Int, value: Boolean): Boolean =
        writeInt(propId, areaId, if (value) 1 else 0)

    private fun writeBytes(payload: ByteArray, propId: Int, areaId: Int, onOk: () -> Unit): Boolean {
        if (!connected.get() || !streamAlive.get()) return false
        val obs = setRequestObserver ?: return false
        return try {
            val done = CountDownLatch(1)
            var ok = false
            writeExecutor.execute {
                try {
                    obs.onNext(payload)
                    ok = true
                    onOk()
                } catch (t: Throwable) {
                    Log.w(TAG, "write 0x${propId.toString(16)}/$areaId failed: ${t.message}")
                } finally {
                    done.countDown()
                }
            }
            done.await(2, TimeUnit.SECONDS)
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "write schedule failed: ${t.message}")
            false
        }
    }

    override fun close() {
        connected.set(false)
        closeQuietly()
        writeExecutor.shutdownNow()
    }

    private fun closeQuietly() {
        try {
            setRequestObserver?.onCompleted()
        } catch (_: Throwable) {
        }
        setRequestObserver = null
        try {
            channel?.shutdownNow()
        } catch (_: Throwable) {
        }
        channel = null
        streamAlive.set(false)
    }

    companion object {
        private const val TAG = "OaaGrpcVhal"
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 40004

        private val SESSION_KEY = Metadata.Key.of("session_id", Metadata.ASCII_STRING_MARSHALLER)
        private val CLIENT_KEY = Metadata.Key.of("client_id", Metadata.ASCII_STRING_MARSHALLER)

        private val BYTES_MARSHALLER = object : MethodDescriptor.Marshaller<ByteArray> {
            override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)
            override fun parse(stream: InputStream): ByteArray = stream.readBytes()
        }

        private fun method(
            name: String,
            type: MethodDescriptor.MethodType,
        ): MethodDescriptor<ByteArray, ByteArray> =
            MethodDescriptor.newBuilder<ByteArray, ByteArray>()
                .setType(type)
                .setFullMethodName(
                    MethodDescriptor.generateFullMethodName("vhal_proto.VehicleServer", name),
                )
                .setRequestMarshaller(BYTES_MARSHALLER)
                .setResponseMarshaller(BYTES_MARSHALLER)
                .build()

        private val SET_PROPERTY = method("SetProperty", MethodDescriptor.MethodType.BIDI_STREAMING)
        private val START_STREAM =
            method("StartPropertyValuesStream", MethodDescriptor.MethodType.SERVER_STREAMING)
        private val SEND_ALL =
            method("SendAllPropertyValuesToStream", MethodDescriptor.MethodType.UNARY)

        private fun cacheKey(propId: Int, areaId: Int): Long =
            (propId.toLong() shl 32) or (areaId.toLong() and 0xffffffffL)
    }
}
