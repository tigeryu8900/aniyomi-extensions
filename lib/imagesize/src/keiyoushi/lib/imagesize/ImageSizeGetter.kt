package keiyoushi.lib.imagesize

import java.io.InputStream

abstract class ImageSizeGetter(
    val stream: InputStream,
) {
    fun get(): Pair<Int, Int>? = try {
        if (validate()) {
            calculate()
        } else {
            null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    } finally {
        stream.close()
    }

    protected abstract fun validate(): Boolean

    protected abstract fun calculate(): Pair<Int, Int>

    protected var offset = 0

    protected fun read(b: ByteArray, off: Int) {
        if (offset > off) {
            throw IndexOutOfBoundsException()
        }

        offset += stream.skip((off - offset).toLong()).toInt()

        if (offset != off) {
            throw IndexOutOfBoundsException()
        }

        offset += stream.read(b)

        if (offset != off + b.size) {
            throw IndexOutOfBoundsException()
        }
    }

    protected fun read(off: Int, len: Int): ByteArray {
        val b = ByteArray(len)
        read(b, off)
        return b
    }

    protected fun compare(cmp: ByteArray, off: Int): Boolean {
        val b = read(off, cmp.size)
        for (i in 0..<b.size) {
            if (b[i] != cmp[i]) {
                return false
            }
        }
        return true
    }

    protected fun readUint8(off: Int): UByte = read(off, 1)[0].toUByte()

    protected fun readInt8(off: Int): Byte = read(off, 1)[0]

    protected fun readUint16LE(off: Int): UInt {
        val b = read(off, 2)
        return b[0].toUByte().toUInt() or (b[1].toUByte().toUInt() shl 8)
    }

    protected fun readUint16BE(off: Int): UInt {
        val b = read(off, 2)
        return b[1].toUByte().toUInt() or (b[0].toUByte().toUInt() shl 8)
    }

    protected fun readInt16LE(off: Int): Int {
        val b = read(off, 2)
        return b[0].toUByte().toInt() or (b[1].toInt() shl 8)
    }

    protected fun readInt16BE(off: Int): Int {
        val b = read(off, 2)
        return b[1].toUByte().toInt() or (b[0].toInt() shl 8)
    }

    protected fun readUint24LE(off: Int): UInt {
        val b = read(off, 3)
        return b[0].toUByte().toUInt() or (b[1].toUByte().toUInt() shl 8) or (b[2].toUByte().toUInt() shl 16)
    }

    protected fun readUint24BE(off: Int): UInt {
        val b = read(off, 3)
        return b[2].toUByte().toUInt() or (b[1].toUByte().toUInt() shl 8) or (b[0].toUByte().toUInt() shl 16)
    }

    protected fun readInt24LE(off: Int): Int {
        val b = read(off, 3)
        return b[0].toUByte().toInt() or (b[1].toUByte().toInt() shl 8) or (b[2].toInt() shl 16)
    }

    protected fun readInt24BE(off: Int): Int {
        val b = read(off, 3)
        return b[2].toUByte().toInt() or (b[1].toUByte().toInt() shl 8) or (b[0].toInt() shl 16)
    }

    protected fun readUint32LE(off: Int): UInt {
        val b = read(off, 4)
        return b[0].toUByte().toUInt() or (b[1].toUByte().toUInt() shl 8) or (b[2].toUByte().toUInt() shl 16) or (b[3].toUByte().toUInt() shl 24)
    }

    protected fun readUint32BE(off: Int): UInt {
        val b = read(off, 4)
        return b[3].toUByte().toUInt() or (b[2].toUByte().toUInt() shl 8) or (b[1].toUByte().toUInt() shl 16) or (b[0].toUByte().toUInt() shl 24)
    }

    protected fun readInt32LE(off: Int): Int {
        val b = read(off, 4)
        return b[0].toUByte().toInt() or (b[1].toUByte().toInt() shl 8) or (b[2].toUByte().toInt() shl 16) or (b[3].toInt() shl 24)
    }

    protected fun readInt32BE(off: Int): Int {
        val b = read(off, 4)
        return b[3].toUByte().toInt() or (b[2].toUByte().toInt() shl 8) or (b[1].toUByte().toInt() shl 16) or (b[0].toInt() shl 24)
    }
}
