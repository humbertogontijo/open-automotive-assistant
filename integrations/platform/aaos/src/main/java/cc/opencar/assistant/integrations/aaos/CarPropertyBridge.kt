package cc.opencar.assistant.integrations.aaos

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Reflective bridge to AAOS Car / CarPropertyManager so compile-time stubs are never packaged.
 */
class CarPropertyBridge(context: Context) : AutoCloseable {
    private val car: Any?
    private val propertyManager: Any?
    private val getProperty2: Method?
    private val getProperty3: Method?
    private val setIntMethod: Method?
    private val setFloatMethod: Method?
    private val setBooleanMethod: Method?
    private val setPropertyMethod: Method?

    val available: Boolean get() = propertyManager != null

    init {
        var carInstance: Any? = null
        var mgr: Any? = null
        var get2: Method? = null
        var get3: Method? = null
        var setInt: Method? = null
        var setFloat: Method? = null
        var setBool: Method? = null
        var setProp: Method? = null
        try {
            val carClass = Class.forName("android.car.Car")
            val create = carClass.getMethod("createCar", Context::class.java)
            carInstance = create.invoke(null, context.applicationContext)
            if (carInstance != null) {
                val getManager = carClass.getMethod("getCarManager", String::class.java)
                mgr = getManager.invoke(carInstance, "property")
                    ?: getManager.invoke(carInstance, "android.car.property.CarPropertyManager")
                if (mgr != null) {
                    val mgrClass = mgr.javaClass
                    get2 = mgrClass.methods.firstOrNull {
                        it.name == "getProperty" && it.parameterTypes.size == 2 &&
                            it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            it.parameterTypes[1] == Int::class.javaPrimitiveType
                    }
                    get3 = mgrClass.methods.firstOrNull {
                        it.name == "getProperty" && it.parameterTypes.size == 3 &&
                            it.parameterTypes[0] == Class::class.java
                    }
                    setInt = mgrClass.methods.firstOrNull {
                        it.name == "setIntProperty" && it.parameterTypes.size == 3
                    }
                    setFloat = mgrClass.methods.firstOrNull {
                        it.name == "setFloatProperty" && it.parameterTypes.size == 3
                    }
                    setBool = mgrClass.methods.firstOrNull {
                        it.name == "setBooleanProperty" && it.parameterTypes.size == 3
                    }
                    setProp = mgrClass.methods.firstOrNull {
                        it.name == "setProperty" && it.parameterTypes.size == 4
                    }
                    Log.i(TAG, "Car bridge ready get2=${get2 != null} get3=${get3 != null}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "CarPropertyBridge unavailable: ${unwrap(t)}")
        }
        car = carInstance
        propertyManager = mgr
        getProperty2 = get2
        getProperty3 = get3
        setIntMethod = setInt
        setFloatMethod = setFloat
        setBooleanMethod = setBool
        setPropertyMethod = setProp
    }

    fun read(propId: Int, areaId: Int): Any? =
        when (val r = readDetailed(propId, areaId)) {
            is DetailedRead.Ok -> r.value
            else -> null
        }

    /** propId → read path that last succeeded ([GET2] or an index into [READ_CLASSES]). */
    private val readPath = ConcurrentHashMap<Int, Int>()

    fun readDetailed(propId: Int, areaId: Int): DetailedRead {
        val mgr = propertyManager ?: return DetailedRead.Unavailable
        readPath[propId]?.let { path ->
            val r = readVia(mgr, path, propId, areaId)
            if (r is DetailedRead.Ok) return r
        }
        var lastDenied: DetailedRead.Denied? = null
        var lastFail: String? = null
        for (path in READ_PATHS) {
            if ((if (path == GET2) getProperty2 else getProperty3) == null) continue
            when (val r = readVia(mgr, path, propId, areaId)) {
                is DetailedRead.Ok -> {
                    readPath[propId] = path
                    return r
                }
                is DetailedRead.Denied -> if (path == GET2) return r else lastDenied = r
                is DetailedRead.Failed -> if (path != GET2) lastFail = r.message
                DetailedRead.Empty -> if (path == GET2) return r
                DetailedRead.Unavailable -> Unit
            }
        }
        return lastDenied ?: lastFail?.let { DetailedRead.Failed(it) } ?: DetailedRead.Empty
    }

    private fun readVia(mgr: Any, path: Int, propId: Int, areaId: Int): DetailedRead = try {
        val result = if (path == GET2) {
            getProperty2?.invoke(mgr, propId, areaId)
        } else {
            getProperty3?.invoke(mgr, READ_CLASSES[path], propId, areaId)
        }
        if (result == null) DetailedRead.Empty else DetailedRead.Ok(extractValue(result))
    } catch (t: Throwable) {
        val cause = root(t)
        if (cause is SecurityException || cause.javaClass.simpleName.contains("Security")) {
            DetailedRead.Denied(extractPermission(cause.message), cause.message)
        } else {
            if (path == GET2) Log.d(TAG, "get2 0x${propId.toString(16)}: ${unwrap(t)}")
            DetailedRead.Failed(cause.message)
        }
    }

    sealed class DetailedRead {
        data class Ok(val value: Any?) : DetailedRead()
        data class Denied(val permission: String?, val message: String?) : DetailedRead()
        data class Failed(val message: String?) : DetailedRead()
        data object Empty : DetailedRead()
        data object Unavailable : DetailedRead()
    }

    fun writeInt(propId: Int, areaId: Int, value: Int): Boolean {
        val mgr = propertyManager ?: return false
        return try {
            when {
                setIntMethod != null -> {
                    setIntMethod.invoke(mgr, propId, areaId, value)
                    true
                }
                setPropertyMethod != null -> {
                    setPropertyMethod.invoke(mgr, Integer::class.java, propId, areaId, value)
                    true
                }
                else -> false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "writeInt failed: ${unwrap(t)}")
            false
        }
    }

    fun writeFloat(propId: Int, areaId: Int, value: Float): Boolean {
        val mgr = propertyManager ?: return false
        return try {
            when {
                setFloatMethod != null -> {
                    setFloatMethod.invoke(mgr, propId, areaId, value)
                    true
                }
                setPropertyMethod != null -> {
                    setPropertyMethod.invoke(mgr, java.lang.Float::class.java, propId, areaId, value)
                    true
                }
                else -> false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "writeFloat failed: ${unwrap(t)}")
            false
        }
    }

    fun writeBoolean(propId: Int, areaId: Int, value: Boolean): Boolean {
        val mgr = propertyManager ?: return false
        return try {
            when {
                setBooleanMethod != null -> {
                    setBooleanMethod.invoke(mgr, propId, areaId, value)
                    true
                }
                setPropertyMethod != null -> {
                    setPropertyMethod.invoke(mgr, java.lang.Boolean::class.java, propId, areaId, value)
                    true
                }
                else -> writeInt(propId, areaId, if (value) 1 else 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "writeBoolean failed: ${unwrap(t)}")
            false
        }
    }

    @Volatile private var propertyCallback: Any? = null

    /**
     * Best-effort reflective registerCallback. Returns false if the platform
     * CarPropertyManager API is unavailable — callers should poll instead.
     */
    fun registerCallback(propIds: IntArray, rateHz: Float, onChange: (propId: Int, value: Any?) -> Unit): Boolean {
        val mgr = propertyManager ?: return false
        return try {
            val callbackClass = Class.forName(
                "android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback",
            )
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass),
            ) { _, method, args ->
                if (method.name == "onChangeEvent" && args != null && args.isNotEmpty()) {
                    val event = args[0] ?: return@newProxyInstance null
                    val getValue = noArgMethod(event.javaClass, "getValue")
                    val pid = (noArgMethod(event.javaClass, "getPropertyId")?.invoke(event) as? Number)?.toInt()
                    val value = getValue?.invoke(event)?.let { extractValue(it) }
                        ?: extractValue(event)
                    if (pid != null) onChange(pid, value)
                }
                null
            }
            val register = mgr.javaClass.methods.firstOrNull {
                it.name == "registerCallback" && it.parameterTypes.size >= 3
            } ?: return false
            for (id in propIds) {
                register.invoke(mgr, proxy, id, rateHz)
            }
            propertyCallback = proxy
            true
        } catch (t: Throwable) {
            Log.d(TAG, "registerCallback unavailable: ${unwrap(t)}")
            false
        }
    }

    override fun close() {
        val cb = propertyCallback
        propertyCallback = null
        val mgr = propertyManager
        if (cb != null && mgr != null) {
            try {
                val unregister = mgr.javaClass.methods.firstOrNull {
                    it.name == "unregisterCallback" && it.parameterTypes.size == 1
                }
                unregister?.invoke(mgr, cb)
            } catch (_: Throwable) {
            }
        }
        try {
            car?.javaClass?.getMethod("disconnect")?.invoke(car)
        } catch (_: Throwable) {
        }
    }

    private fun extractValue(result: Any): Any? =
        noArgMethod(result.javaClass, "getValue")?.invoke(result) ?: result

    private val noArgMethods = ConcurrentHashMap<Pair<Class<*>, String>, Any>()

    private fun noArgMethod(cls: Class<*>, name: String): Method? =
        noArgMethods.getOrPut(cls to name) {
            cls.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() } ?: NO_METHOD
        } as? Method

    private fun root(t: Throwable): Throwable {
        var c: Throwable = if (t is InvocationTargetException) t.targetException ?: t else t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return c
    }

    private fun extractPermission(message: String?): String? {
        if (message == null) return null
        val m = Regex("""requires\s+([\w.]+)""").find(message)
        return m?.groupValues?.getOrNull(1) ?: message.take(120)
    }

    private fun unwrap(t: Throwable): String {
        val cause = root(t)
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    companion object {
        private const val TAG = "OaaCarBridge"
        private const val GET2 = -1
        private val READ_CLASSES = arrayOf(
            java.lang.Integer::class.java,
            java.lang.Float::class.java,
            java.lang.Boolean::class.java,
            java.lang.Long::class.java,
            String::class.java,
        )
        private val READ_PATHS = listOf(GET2) + READ_CLASSES.indices
        private val NO_METHOD = Any()
    }
}
