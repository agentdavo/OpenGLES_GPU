package gpu

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._
import spinal.core.sim._

class FpuCore extends Component {
  val INPUT = Payload(Vec(Bits(32 bits), 16))
  val DECODED = Payload(Vec(FpuTypes.SIMDComponents(), 16))
  val RESULT = Payload(Vec(Bits(32 bits), 16))

  val io = new Bundle {
    val input = slave Stream(INPUT)
    val output = master Stream(RESULT)
    val rst = in(Bool())
    val clk = in(Bool())
    val reset = in(Bool())
    val debugDecoded = out Vec(FpuTypes.SIMDComponents(), 16)
  }

  val cd = ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  val area = cd on new Area {
    val n0 = Node()
    val n1 = Node()
    val n2 = Node()

    val s0 = StageLink(n0, n1)
    val s1 = StageLink(n1, n2)

    n0.arbitrateFrom(io.input)
    n0(INPUT) := io.input.payload
    when(io.rst) { n0.valid := False }

    val decodeArea = new Area {
      n1(DECODED) := Vec((0 until 16).map { i =>
        val dec = FpuTypes.SIMDComponents()
        dec.sign := n1(INPUT)(i)(31)
        dec.exponent := n1(INPUT)(i)(30 downto 23).resize(11)
        dec.mantissa := n1(INPUT)(i)(22 downto 0).resize(52)
        dec.precision := FpuTypes.Precision.FP32
        dec.state := FpuTypes.FormatState.NORMAL
        dec.isInteger := False
        dec.intValue := 0

        when(n1(INPUT)(i)(31 downto 0) === B"32'h000000ff") {
          dec.isInteger := True
          dec.intValue := S(255, 64 bits)
          dec.exponent := 0
          dec.mantissa := 0
        } elsewhen(n1(INPUT)(i)(30 downto 23) === 0 && n1(INPUT)(i)(22 downto 0) =/= 0) {
          dec.state := FpuTypes.FormatState.NORMAL
        } elsewhen(n1(INPUT)(i)(30 downto 23) === 0xFF) {
          when(n1(INPUT)(i)(22 downto 0) =/= 0) {
            dec.state := FpuTypes.FormatState.NAN
          } otherwise {
            dec.state := FpuTypes.FormatState.INFINITY
          }
        } elsewhen(n1(INPUT)(i) === 0) {
          dec.state := FpuTypes.FormatState.ZERO
        }
        dec
      })
      io.debugDecoded := n1(DECODED)
      io.debugDecoded.simPublic()
    }

    val processArea = new Area {
      n2(RESULT) := Vec((0 until 16).map { i =>
        val comp = FpuTypes.SIMDComponents()
        comp.assignFrom(n2(DECODED)(i))
        when(!comp.isInteger && comp.state === FpuTypes.FormatState.NORMAL) {
          comp.sign := !n2(DECODED)(i).sign
        }
        val codec = FpuTypes.codecs(FpuTypes.Precision.FP32)
        val encoded = codec.encode(comp, FpuTypes.RoundMode.RNE).value.resize(32)
        (comp.isInteger || comp.state =/= FpuTypes.FormatState.NORMAL) ? n2(INPUT)(i) | encoded
      })
      n2.arbitrateTo(io.output)
      io.output.payload := n2(RESULT)
    }

    Builder(s0, s1)
  }
}

object FpuCoreSim extends App {
  SimConfig.withWave.compile(new FpuCore).doSim { dut =>
    val cd = ClockDomain(dut.io.clk, dut.io.reset)
    cd.forkStimulus(10)

    var cycle = 0

    fork {
      dut.io.input.valid #= false
      cd.waitSampling(2)

      dut.io.input.payload(0) #= BigInt("3f800000", 16)
      dut.io.input.payload(1) #= BigInt("ff", 16)
      dut.io.input.payload(2) #= BigInt("800000", 16)
      dut.io.input.payload(3) #= BigInt("7fc00000", 16)
      dut.io.input.payload(4) #= BigInt("7f800001", 16)
      dut.io.input.payload(5) #= BigInt("ffc00000", 16)
      dut.io.input.payload(6) #= BigInt("ff800001", 16)
      for (i <- 7 until 16) { dut.io.input.payload(i) #= 0 }

      dut.io.input.valid #= true
      cd.waitSamplingWhere(dut.io.input.ready.toBoolean)
      dut.io.input.valid #= false
    }

    dut.io.rst #= true
    cd.waitSampling(1)
    cycle = 1
    println(s"\nCycle $cycle (Reset):")
    println("Input Valid: " + dut.io.input.valid.toBoolean)
    println("Input Ready: " + dut.io.input.ready.toBoolean)
    println("Output Valid: " + dut.io.output.valid.toBoolean)
    println("Output Ready: " + dut.io.output.ready.toBoolean)
    println(s"Inputs: ${dut.io.input.payload.map(_.toBigInt.toString(16)).take(7).mkString(", ")}")
    println(s"Decoded: ${dut.io.debugDecoded.map(d => s"sign=${d.sign.toBoolean}, exp=${d.exponent.toBigInt.toString(16)}, mant=${d.mantissa.toBigInt.toString(16).take(6)}, state=${d.state.toString}, isInt=${d.isInteger.toBoolean}").take(7).mkString(", ")}")
    println(s"Outputs: ${dut.io.output.payload.map(_.toBigInt.toString(16)).take(7).mkString(", ")}")

    cd.waitSampling(1)
    cycle = 2
    dut.io.rst #= false
    println(s"\nCycle $cycle (Reset Off):")
    println("Input Valid: " + dut.io.input.valid.toBoolean)
    println("Input Ready: " + dut.io.input.ready.toBoolean)
    println("Output Valid: " + dut.io.output.valid.toBoolean)
    println("Output Ready: " + dut.io.output.ready.toBoolean)
    println(s"Inputs: ${dut.io.input.payload.map(_.toBigInt.toString(16)).take(7).mkString(", ")}")
    println(s"Decoded: ${dut.io.debugDecoded.map(d => s"sign=${d.sign.toBoolean}, exp=${d.exponent.toBigInt.toString(16)}, mant=${d.mantissa.toBigInt.toString(16).take(6)}, state=${d.state.toString}, isInt=${d.isInteger.toBoolean}").take(7).mkString(", ")}")
    println(s"Outputs: ${dut.io.output.payload.map(_.toBigInt.toString(16)).take(7).mkString(", ")}")

    fork {
      for (c <- 3 to 5) {
        cd.waitSampling(1)
        cycle = c
        println(s"\nCycle $cycle:")
        println("Input Valid: " + dut.io.input.valid.toBoolean)
        println("Input Ready: " + dut.io.input.ready.toBoolean)
        println("Output Valid: " + dut.io.output.valid.toBoolean)
        println("Output Ready: " + dut.io.output.ready.toBoolean)
        println(s"Inputs: ${dut.io.input.payload.map(_.toBigInt.toString(16)).take(7).mkString(", ")}")
        println(s"Decoded: ${dut.io.debugDecoded.map(d => s"sign=${d.sign.toBoolean}, exp=${d.exponent.toBigInt.toString(16)}, mant=${d.mantissa.toBigInt.toString(16).take(6)}, state=${d.state.toString}, isInt=${d.isInteger.toBoolean}").take(7).mkString(", ")}")
        println(s"Outputs: ${dut.io.output.payload.map(_.toBigInt.toString(16)).take(7).mkString(", ")}")
      }
    }

    dut.io.output.ready #= true
    cd.waitSampling(3)
    cycle = 5

    println(s"\nVector Inputs (Cycle $cycle):")
    for (i <- 0 until 7) {
      println(s"Lane $i: value=${dut.io.input.payload(i).toBigInt.toString(16)}")
    }

    println(s"\nDecoded Outputs (Cycle $cycle):")
    for (i <- 0 until 7) {
      val dec = dut.io.debugDecoded(i)
      println(s"Lane $i: sign=${dec.sign.toBoolean}, exp=${dec.exponent.toBigInt.toString(16)}, mant=${dec.mantissa.toBigInt.toString(16)}, state=${dec.state.toString}, isInt=${dec.isInteger.toBoolean}")
    }

    println(s"\nPipeline Outputs (Cycle $cycle):")
    for (i <- 0 until 7) {
      println(s"Lane $i: value=${dut.io.output.payload(i).toBigInt.toString(16)}")
    }

    simSuccess()
  }
}