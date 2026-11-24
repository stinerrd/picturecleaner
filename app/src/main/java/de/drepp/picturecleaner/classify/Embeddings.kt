package de.drepp.picturecleaner.classify

import java.nio.ByteBuffer
import java.nio.ByteOrder

fun FloatArray.toBytes(): ByteArray {
    val buffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    buffer.asFloatBuffer().put(this)
    return buffer.array()
}

fun ByteArray.toFloats(): FloatArray {
    val out = FloatArray(size / 4)
    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
    return out
}
