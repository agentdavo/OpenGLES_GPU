package gpu

import spinal.core._
import spinal.core.sim._

object FpuTypesSim extends App {
  SimConfig.withWave.compile(new FpuTypes.FpuTestVectors).doSim { dut =>
    dut.cd.forkStimulus(10)

    // Drive inputVector with test vectors
    dut.inputVector.lanes(0).value #= BigInt("3f800000", 16)
    dut.inputVector.lanes(1).value #= BigInt("ff", 16)
    dut.inputVector.lanes(2).value #= BigInt("800000", 16)
    dut.inputVector.lanes(3).value #= BigInt("7fc00000", 16)
    dut.inputVector.lanes(4).value #= BigInt("7f800001", 16)
    dut.inputVector.lanes(5).value #= BigInt("ffc00000", 16)
    dut.inputVector.lanes(6).value #= BigInt("ff800001", 16)
    for (i <- 7 until 16) { dut.inputVector.lanes(i).value #= 0 }

    dut.cd.waitSampling(5)

    println("Test Vectors:")
    println(s"fp32_1_0: ${dut.vectors.fp32_1_0.toBigInt.toString(16)}")
    println(s"int32_255: ${dut.vectors.int32_255.toBigInt.toString(16)}")
    println(s"fp32_subnormal: ${dut.vectors.fp32_subnormal.toBigInt.toString(16)}")
    println(s"fp32_quiet_nan: ${dut.vectors.fp32_quiet_nan.toBigInt.toString(16)}")
    println(s"fp32_signaling_nan: ${dut.vectors.fp32_signaling_nan.toBigInt.toString(16)}")
    println(s"fp32_neg_quiet_nan: ${dut.vectors.fp32_neg_quiet_nan.toBigInt.toString(16)}")
    println(s"fp32_neg_signaling_nan: ${dut.vectors.fp32_neg_signaling_nan.toBigInt.toString(16)}")

    println("\nDecoded Register:")
    for (i <- 0 until 7) {
      val dec = dut.decodedReg(i)
      println(s"Lane $i: sign=${dec.sign.toBoolean}, exp=${dec.exponent.toBigInt.toString(16)}, mant=${dec.mantissa.toBigInt.toString(16).take(6)}, state=${if ((dec.state === FpuTypes.FormatState.NORMAL).toBoolean) "NORMAL" else if ((dec.state === FpuTypes.FormatState.NAN).toBoolean) "NAN" else if ((dec.state === FpuTypes.FormatState.INFINITY).toBoolean) "INF" else "ZERO"}, isInt=${dec.isInteger.toBoolean}")
    }

    simSuccess()
  }
}