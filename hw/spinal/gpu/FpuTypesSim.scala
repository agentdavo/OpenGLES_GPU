package gpu

import spinal.core._
import spinal.core.sim._

object FpuTypesSim extends App {
  SimConfig.withWave.compile {
    val dut = new FpuTypes.FpuTestVectors
    for (lane <- dut.io.inputVector.lanes) {
      lane.precision.simPublic()
      lane.value.simPublic()
    }
    dut.decodedReg.simPublic()
    dut.decodedOut.simPublic()
    dut
  }.doSim { dut =>
    dut.cd.forkStimulus(10)

    // Reset to clear state
    dut.io.rst #= true
    dut.cd.waitSampling(2)
    dut.io.rst #= false
    dut.cd.waitSampling(2) // Ensure reset stabilizes

    // Test vectors for each precision type
    val testVectors = Map(
      FpuTypes.Precision.FP8E4M3 -> List(
        BigInt("00", 16),       // Zero
        BigInt("08", 16),       // Normal (1.0)
        BigInt("01", 16),       // Subnormal
        BigInt("78", 16),       // Infinity
        BigInt("7C", 16)        // NaN
      ),
      FpuTypes.Precision.FP8E5M2 -> List(
        BigInt("00", 16),       // Zero
        BigInt("20", 16),       // Normal (1.0)
        BigInt("01", 16),       // Subnormal
        BigInt("7C", 16),       // Infinity
        BigInt("7E", 16)        // NaN
      ),
      FpuTypes.Precision.FP16 -> List(
        BigInt("0000", 16),     // Zero
        BigInt("3C00", 16),     // Normal (1.0)
        BigInt("0001", 16),     // Subnormal
        BigInt("7C00", 16),     // Infinity
        BigInt("7E00", 16)      // NaN
      ),
      FpuTypes.Precision.BF16 -> List(
        BigInt("0000", 16),     // Zero
        BigInt("3F80", 16),     // Normal (1.0)
        BigInt("0080", 16),     // Subnormal
        BigInt("7F80", 16),     // Infinity
        BigInt("7FC0", 16)      // NaN
      ),
      FpuTypes.Precision.TF32 -> List(
        BigInt("00000000", 16), // Zero
        BigInt("3F800000", 16), // Normal (1.0)
        BigInt("00800000", 16), // Subnormal
        BigInt("7F800000", 16), // Infinity
        BigInt("7FC00000", 16)  // NaN
      ),
      FpuTypes.Precision.FP32 -> List(
        BigInt("00000000", 16), // Zero
        BigInt("3F800000", 16), // Normal (1.0)
        BigInt("00800000", 16), // Subnormal
        BigInt("7F800000", 16), // Infinity
        BigInt("7FC00000", 16), // NaN
        BigInt("FFC00000", 16), // -NaN
        BigInt("FF800001", 16)  // -sNaN
      ),
      FpuTypes.Precision.FP64 -> List(
        BigInt("0000000000000000", 16), // Zero
        BigInt("3FF0000000000000", 16), // Normal (1.0)
        BigInt("0008000000000000", 16), // Subnormal
        BigInt("7FF0000000000000", 16), // Infinity
        BigInt("7FF8000000000000", 16)  // NaN
      ),
      FpuTypes.Precision.INT8 -> List(
        BigInt("00", 16),       // Zero
        BigInt("7F", 16),       // Max positive (127)
        BigInt("80", 16)        // Min negative (-128)
      ),
      FpuTypes.Precision.UINT8 -> List(
        BigInt("00", 16),       // Zero
        BigInt("FF", 16)        // Max (255)
      ),
      FpuTypes.Precision.INT16 -> List(
        BigInt("0000", 16),     // Zero
        BigInt("7FFF", 16),     // Max positive (32767)
        BigInt("8000", 16)      // Min negative (-32768)
      ),
      FpuTypes.Precision.UINT16 -> List(
        BigInt("0000", 16),     // Zero
        BigInt("FFFF", 16)      // Max (65535)
      ),
      FpuTypes.Precision.INT32 -> List(
        BigInt("00000000", 16), // Zero
        BigInt("7FFFFFFF", 16), // Max positive (2147483647)
        BigInt("80000000", 16)  // Min negative (-2147483648)
      ),
      FpuTypes.Precision.UINT32 -> List(
        BigInt("00000000", 16), // Zero
        BigInt("FFFFFFFF", 16)  // Max (4294967295)
      ),
      FpuTypes.Precision.INT64 -> List(
        BigInt("0000000000000000", 16), // Zero
        BigInt("7FFFFFFFFFFFFFFF", 16), // Max positive
        BigInt("8000000000000000", 16)  // Min negative
      ),
      FpuTypes.Precision.UINT64 -> List(
        BigInt("0000000000000000", 16), // Zero
        BigInt("FFFFFFFFFFFFFFFF", 16)  // Max
      )
    )

    // Drive inputVector all at once
    var laneIdx = 0
    testVectors.foreach { case (precision, values) =>
      values.foreach { value =>
        if (laneIdx < 16) {
          dut.io.inputVector.lanes(laneIdx).precision #= precision
          dut.io.inputVector.lanes(laneIdx).value #= value
          laneIdx += 1
        }
      }
    }

    dut.cd.waitSampling(3) // Ensure RegNext propagation

    // Helper to map state bits to string (binary sequential: ZERO=00, INFINITY=01, NAN=10, NORMAL=11)
    def getStateString(state: FpuTypes.FormatState.C): String = {
      state.toBigInt match {
        case n if n == BigInt(0) => "ZERO"
        case n if n == BigInt(1) => "INFINITY"
        case n if n == BigInt(2) => "NAN"
        case n if n == BigInt(3) => "NORMAL"
        case _ => "UNKNOWN"
      }
    }

    println("Input Vector (Verification):")
    for (i <- 0 until 16) {
      val lane = dut.io.inputVector.lanes(i)
      println(s"Lane $i: precision=${lane.precision.toEnum}, value=${lane.value.toBigInt.toString(16)}")
    }

    println("\nDirect Decode Output:")
    for (i <- 0 until 16) {
      val dec = dut.decodedOut(i)
      println(s"Lane $i: precision=${dec.precision.toEnum}, sign=${dec.sign.toBoolean}, exp=${dec.exponent.toBigInt.toString(16)}, mant=${dec.mantissa.toBigInt.toString(16).take(6)}, state=${getStateString(dec.state)}, isInt=${dec.isInteger.toBoolean}, intValue=${dec.intValue.toBigInt.toString(16)}")
    }

    println("\nDecoded Register (After RegNext):")
    for (i <- 0 until 16) {
      val dec = dut.decodedReg(i)
      println(s"Lane $i: precision=${dec.precision.toEnum}, sign=${dec.sign.toBoolean}, exp=${dec.exponent.toBigInt.toString(16)}, mant=${dec.mantissa.toBigInt.toString(16).take(6)}, state=${getStateString(dec.state)}, isInt=${dec.isInteger.toBoolean}, intValue=${dec.intValue.toBigInt.toString(16)}")
    }

    simSuccess()
  }
}