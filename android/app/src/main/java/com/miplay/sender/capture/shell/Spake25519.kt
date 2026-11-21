package com.miplay.sender.capture.shell

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 与 BoringSSL `crypto/curve25519/spake25519.cc` 逐字节对齐的纯 Kotlin SPAKE2-Ed25519 实现。
 *
 * 关键兼容性细节（源自 BoringSSL 与 AOSP `pairing_auth.cpp`）：
 * 1. 使用 BoringSSL 专有生成点 `M` / `N`（非 RFC 9382 生成点）；
 * 2. 遵循 BoringSSL 的 `password_scalar` 8 倍数对齐处理（`add L / 2L / 4L` 使低 3 bit 为 0）；
 * 3. Transcript 顺序为 `aliceName, bobName, aliceMsg(32B), bobMsg(32B), dhShared(32B), passwordHash64(64B)`，
 *    每段前加 8 字节小端长度前缀并用 SHA-512 摘要。
 */
class Spake25519(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
) {
    enum class Role { ALICE, BOB }

    private var privateScalar: BigInteger? = null
    private var passwordScalar: BigInteger? = null
    private var passwordHash64: ByteArray? = null
    private var myMsg: ByteArray? = null
    private var state: Int = STATE_INIT

    fun generateMsg(password: ByteArray, random64: ByteArray = randomBytes(64)): ByteArray {
        check(state == STATE_INIT) { "SPAKE2 generateMsg 仅允许调用一次" }
        require(random64.size == 64) { "random64 必须为 64 字节" }

        // 等价于 BoringSSL: x25519_sc_reduce(private_tmp); left_shift_3(private_tmp);
        val priv = leBytesToBigInt(random64).mod(L).shiftLeft(3)
        privateScalar = priv

        val pwHash = sha512(password)
        passwordHash64 = pwHash
        val pwScalar = computeBoringSslPasswordScalar(pwHash)
        passwordScalar = pwScalar

        val p = Edwards25519.scalarMult(Edwards25519.B, priv)
        val maskPoint = if (role == Role.ALICE) Edwards25519.M else Edwards25519.N
        val mask = Edwards25519.scalarMult(maskPoint, pwScalar)
        val pStar = Edwards25519.add(p, mask)
        val encoded = Edwards25519.encode(pStar)

        myMsg = encoded
        state = STATE_MSG_GENERATED
        return encoded.copyOf()
    }

    fun processMsg(theirMsg: ByteArray): ByteArray {
        check(state == STATE_MSG_GENERATED) { "须先调用 generateMsg 再调用 processMsg" }
        require(theirMsg.size == 32) { "对端 SPAKE2 消息长度必须为 32 字节" }

        val qStar = Edwards25519.decode(theirMsg)
            ?: throw IllegalArgumentException("对端 SPAKE2 点不在 Ed25519 曲线上")
        val pwScalar = requireNotNull(passwordScalar)
        val priv = requireNotNull(privateScalar)
        val localMsg = requireNotNull(myMsg)
        val pwHash = requireNotNull(passwordHash64)

        val peerMaskPoint = if (role == Role.ALICE) Edwards25519.N else Edwards25519.M
        val peerMask = Edwards25519.scalarMult(peerMaskPoint, pwScalar)
        val q = Edwards25519.sub(qStar, peerMask)
        val dhShared = Edwards25519.encode(Edwards25519.scalarMult(q, priv))

        val md = MessageDigest.getInstance("SHA-512")
        if (role == Role.ALICE) {
            updateWithLeLengthPrefix(md, myName)
            updateWithLeLengthPrefix(md, theirName)
            updateWithLeLengthPrefix(md, localMsg)
            updateWithLeLengthPrefix(md, theirMsg)
        } else {
            updateWithLeLengthPrefix(md, theirName)
            updateWithLeLengthPrefix(md, myName)
            updateWithLeLengthPrefix(md, theirMsg)
            updateWithLeLengthPrefix(md, localMsg)
        }
        updateWithLeLengthPrefix(md, dhShared)
        updateWithLeLengthPrefix(md, pwHash)

        state = STATE_KEY_GENERATED
        return md.digest()
    }

    internal companion object {
        private const val STATE_INIT = 0
        private const val STATE_MSG_GENERATED = 1
        private const val STATE_KEY_GENERATED = 2

        /** Ed25519 素数阶子群阶数 L = 2^252 + 27742317777372353535851937790883648493 */
        val L: BigInteger = BigInteger.ONE.shiftLeft(252)
            .add(BigInteger("27742317777372353535851937790883648493"))

        /** BoringSSL spake25519.cc 中的 password_scalar 8 倍数对齐逻辑。 */
        fun computeBoringSslPasswordScalar(passwordHash64: ByteArray): BigInteger {
            var s = leBytesToBigInt(passwordHash64).mod(L)
            if (s.testBit(0)) s = s.add(L)
            if (s.testBit(1)) s = s.add(L.shiftLeft(1))
            if (s.testBit(2)) s = s.add(L.shiftLeft(2))
            check(s.and(BigInteger.valueOf(7)) == BigInteger.ZERO)
            return s
        }

        private fun updateWithLeLengthPrefix(md: MessageDigest, data: ByteArray) {
            var len = data.size.toLong()
            val prefix = ByteArray(8)
            for (i in 0 until 8) {
                prefix[i] = (len and 0xffL).toByte()
                len = len ushr 8
            }
            md.update(prefix)
            md.update(data)
        }

        private fun sha512(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-512").digest(data)

        private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

        fun leBytesToBigInt(le: ByteArray): BigInteger {
            val be = ByteArray(le.size)
            for (i in le.indices) be[le.size - 1 - i] = le[i]
            return BigInteger(1, be)
        }
    }

    /**
     * Twisted Edwards25519 仿射/射影点运算（用于一次性配对，毫秒级耗时，无任何 native 依赖）。
     * 曲线方程：`-x^2 + y^2 = 1 + d * x^2 * y^2 (mod P)`，其中 `P = 2^255 - 19`。
     */
    internal object Edwards25519 {
        private val TWO: BigInteger = BigInteger.valueOf(2)
        val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        private val D: BigInteger = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P))
            .mod(P)
        private val SQRT_MINUS_ONE: BigInteger = BigInteger.valueOf(2)
            .modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
        private val P_PLUS_3_DIV_8: BigInteger = P.add(BigInteger.valueOf(3)).shiftRight(3)

        data class ExtPoint(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

        val IDENTITY = ExtPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

        val B: ExtPoint = fromAffine(
            BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
            BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"),
        )

        /** BoringSSL `spake25519.cc` 固定生成点 M（encoded: 5ada7e4b...8eed12e）。 */
        val M: ExtPoint = fromAffine(
            BigInteger("31406539342727633121250288103050113562375374900226415211311216773867585644232"),
            BigInteger("21177308356423958466833845032658859666296341766942662650232962324899758529114"),
        )

        /** BoringSSL `spake25519.cc` 固定生成点 N（encoded: 10e3df0a...693bc778）。 */
        val N: ExtPoint = fromAffine(
            BigInteger("49918732221787544735331783592030787422991506689877079631459872391322455579424"),
            BigInteger("54629554431565467720832445949441049581317094546788069926228343916274969994000"),
        )

        private fun fromAffine(x: BigInteger, y: BigInteger): ExtPoint {
            val xm = x.mod(P)
            val ym = y.mod(P)
            return ExtPoint(xm, ym, BigInteger.ONE, xm.multiply(ym).mod(P))
        }

        fun add(p1: ExtPoint, p2: ExtPoint): ExtPoint {
            val a = p1.y.subtract(p1.x).multiply(p2.y.subtract(p2.x)).mod(P)
            val b = p1.y.add(p1.x).multiply(p2.y.add(p2.x)).mod(P)
            val c = p1.t.multiply(TWO).multiply(D).multiply(p2.t).mod(P)
            val d = p1.z.multiply(TWO).multiply(p2.z).mod(P)
            val e = b.subtract(a).mod(P)
            val f = d.subtract(c).mod(P)
            val g = d.add(c).mod(P)
            val h = b.add(a).mod(P)
            return ExtPoint(
                x = e.multiply(f).mod(P),
                y = g.multiply(h).mod(P),
                z = f.multiply(g).mod(P),
                t = e.multiply(h).mod(P),
            )
        }

        fun negate(p: ExtPoint): ExtPoint = ExtPoint(
            x = P.subtract(p.x).mod(P),
            y = p.y,
            z = p.z,
            t = P.subtract(p.t).mod(P),
        )

        fun sub(p1: ExtPoint, p2: ExtPoint): ExtPoint = add(p1, negate(p2))

        fun scalarMult(base: ExtPoint, scalar: BigInteger): ExtPoint {
            var r = IDENTITY
            var q = base
            val bitLen = scalar.bitLength()
            for (i in 0 until bitLen) {
                if (scalar.testBit(i)) {
                    r = add(r, q)
                }
                q = add(q, q)
            }
            return r
        }

        fun encode(p: ExtPoint): ByteArray {
            val zInv = p.z.modInverse(P)
            val x = p.x.multiply(zInv).mod(P)
            val y = p.y.multiply(zInv).mod(P)
            val out = ByteArray(32)
            val yBe = y.toByteArray()
            // 转为 32 字节小端
            for (i in yBe.indices) {
                val pos = yBe.size - 1 - i
                if (pos < 32) {
                    out[pos] = yBe[i]
                }
            }
            if (x.testBit(0)) {
                out[31] = (out[31].toInt() or 0x80).toByte()
            }
            return out
        }

        fun decode(encoded: ByteArray): ExtPoint? {
            if (encoded.size != 32) return null
            val sign = (encoded[31].toInt() ushr 7) and 1
            val yBytes = encoded.copyOf()
            yBytes[31] = (yBytes[31].toInt() and 0x7f).toByte()
            val y = leBytesToBigInt(yBytes)
            if (y >= P) return null

            val y2 = y.multiply(y).mod(P)
            val u = y2.subtract(BigInteger.ONE).mod(P)
            val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
            val vInv = runCatching { v.modInverse(P) }.getOrNull() ?: return null
            val x2 = u.multiply(vInv).mod(P)

            var x = x2.modPow(P_PLUS_3_DIV_8, P)
            if (x.multiply(x).mod(P) != x2) {
                x = x.multiply(SQRT_MINUS_ONE).mod(P)
                if (x.multiply(x).mod(P) != x2) return null
            }
            if (x == BigInteger.ZERO && sign == 1) return null
            if ((if (x.testBit(0)) 1 else 0) != sign) {
                x = P.subtract(x).mod(P)
            }
            return fromAffine(x, y)
        }
    }
}
