package gpu

import spinal.core._
import spinal.lib._

object FpuTypes {
  // Precision enumeration
  object Precision extends SpinalEnum(binarySequential) {
    val FP8E4M3, FP8E5M2, FP16, BF16, TF32, FP32, FP64,
        INT8, UINT8, INT16, UINT16, INT32, UINT32, INT64, UINT64 = newElement()
  }

  // Format state for floating-point numbers
  object FormatState extends SpinalEnum(binarySequential) {
    val ZERO, INFINITY, NAN, NORMAL = newElement()
  }

  // Register index type
  type RegIdx = UInt
  def RegIdx() = UInt(6 bits)

  // Data structure for a single SIMD lane
  case class SIMDData() extends Bundle {
    val precision = Precision()
    val value = Bits(64 bits)
  }

  // Decoded components for SIMD data
  case class SIMDComponents() extends Bundle {
    val precision = Precision()
    val state = FormatState()
    val sign = Bool()
    val exponent = Bits(11 bits)
    val mantissa = Bits(52 bits)
    val isInteger = Bool()
    val intValue = SInt(64 bits)
  }

  // Operation codes
  object FpuOpcode extends SpinalEnum(binarySequential) {
    val ADD, SUB, MUL, DIV, SQRT, CMP, MIN, MAX, ABS, NEG = newElement()
  }

  // Rounding modes
  object RoundMode extends SpinalEnum(binarySequential) {
    val RNE, RTZ, RDN, RUP = newElement()
  }

  // Subnormal and modifier enums
  object SubNormMode extends SpinalEnum(binarySequential) {
    val ENABLED, DISABLED = newElement()
  }
  object Modifiers extends SpinalEnum(binarySequential) {
    val NONE, ROUND = newElement()
  }

  // Codec trait for encoding/decoding
  trait SIMDCodec {
    def decode(bits: Bits): SIMDComponents
    def encode(comp: SIMDComponents, roundMode: RoundMode.E): SIMDData
  }

  // Precision-specific codec
  case class PrecisionCodec(precision: Precision.E, signWidth: Int, expWidth: Int, mantWidth: Int, bias: Int, isFloat: Boolean) extends SIMDCodec {
    def decode(data: Bits): SIMDComponents = {
      val comp = SIMDComponents()
      comp.precision := precision
      comp.isInteger := Bool(!isFloat)

      if (isFloat) {
        val totalWidth = signWidth + expWidth + mantWidth
        val bits = data.takeLow(totalWidth)
        val sign = bits(totalWidth - 1)
        val exp = bits(totalWidth - 2 downto mantWidth)
        val mant = bits(mantWidth - 1 downto 0)
        val expAllOnes = exp === ((1 << expWidth) - 1)
        val mantAllZeros = mant === 0
        val expAllZeros = exp === 0

        comp.sign := sign
        comp.exponent := exp.resize(11)
        comp.mantissa := mant.resize(52)
        comp.intValue := 0

        when(expAllOnes && !mantAllZeros) {
          comp.state := FormatState.NAN
        } elsewhen(expAllOnes && mantAllZeros) {
          comp.state := FormatState.INFINITY
        } elsewhen(expAllZeros && mantAllZeros) {
          comp.state := FormatState.ZERO
        } elsewhen(expAllZeros && !mantAllZeros) {
          comp.state := FormatState.NORMAL // Subnormal
        } otherwise {
          comp.state := FormatState.NORMAL
        }
      } else {
        comp.sign := data.msb
        comp.exponent := 0
        comp.mantissa := 0
        comp.intValue := data.asSInt.resize(64)
        comp.state := FormatState.NORMAL
      }
      comp
    }

    def encode(components: SIMDComponents, roundMode: RoundMode.E): SIMDData = {
      val d = SIMDData()
      d.precision := components.precision
      if (isFloat) {
        when(components.state === FormatState.ZERO) {
          d.value := 0
        } elsewhen(components.state === FormatState.INFINITY) {
          d.value := Cat(components.sign, Bits(expWidth bits).setAll(), Bits(mantWidth bits).clearAll()).resize(64)
        } elsewhen(components.state === FormatState.NAN) {
          d.value := Cat(components.sign, Bits(expWidth bits).setAll(), Bits(mantWidth bits).setAll()).resize(64)
        } otherwise {
          d.value := Cat(components.sign, components.exponent.takeLow(expWidth), components.mantissa.takeLow(mantWidth)).resize(64)
        }
      } else {
        d.value := components.intValue.asBits
      }
      d
    }
  }

  // Codec definitions
  val codecs = Map(
    Precision.FP8E4M3 -> PrecisionCodec(Precision.FP8E4M3, 1, 4,  3,  7,  true),
    Precision.FP8E5M2 -> PrecisionCodec(Precision.FP8E5M2, 1, 5,  2, 15,  true),
    Precision.FP16    -> PrecisionCodec(Precision.FP16,    1, 5,  10, 15,  true),
    Precision.BF16    -> PrecisionCodec(Precision.BF16,    1, 8,  7,  127, true),
    Precision.TF32    -> PrecisionCodec(Precision.TF32,    1, 8,  10, 127, true),
    Precision.FP32    -> PrecisionCodec(Precision.FP32,    1, 8,  23, 127, true),
    Precision.FP64    -> PrecisionCodec(Precision.FP64,    1, 11, 52, 1023, true),
    Precision.INT8    -> PrecisionCodec(Precision.INT8,    1, 0,  7,  0,   false),
    Precision.UINT8   -> PrecisionCodec(Precision.UINT8,   0, 0,  8,  0,   false),
    Precision.INT16   -> PrecisionCodec(Precision.INT16,   1, 0,  15, 0,   false),
    Precision.UINT16  -> PrecisionCodec(Precision.UINT16,  0, 0,  16, 0,   false),
    Precision.INT32   -> PrecisionCodec(Precision.INT32,   1, 0,  31, 0,   false),
    Precision.UINT32  -> PrecisionCodec(Precision.UINT32,  0, 0,  32, 0,   false),
    Precision.INT64   -> PrecisionCodec(Precision.INT64,   1, 0,  63, 0,   false),
    Precision.UINT64  -> PrecisionCodec(Precision.UINT64,  0, 0,  64, 0,   false)
  )

  // SIMD vector with 16 lanes
  case class SIMDVector() extends Bundle {
    val lanes = Vec(SIMDData(), 16)

    def decode(): Vec[SIMDComponents] = {
      Vec(lanes.map { lane =>
        val decoded = SIMDComponents()
        switch(lane.precision) {
          is(Precision.FP8E4M3) { decoded := codecs(Precision.FP8E4M3).decode(lane.value.resize(8)) }
          is(Precision.FP8E5M2) { decoded := codecs(Precision.FP8E5M2).decode(lane.value.resize(8)) }
          is(Precision.FP16)    { decoded := codecs(Precision.FP16).decode(lane.value.resize(16)) }
          is(Precision.BF16)    { decoded := codecs(Precision.BF16).decode(lane.value.resize(16)) }
          is(Precision.TF32)    { decoded := codecs(Precision.TF32).decode(lane.value.resize(19)) }
          is(Precision.FP32)    { decoded := codecs(Precision.FP32).decode(lane.value.resize(32)) }
          is(Precision.FP64)    { decoded := codecs(Precision.FP64).decode(lane.value.resize(64)) }
          is(Precision.INT8)    { decoded := codecs(Precision.INT8).decode(lane.value.resize(8)) }
          is(Precision.UINT8)   { decoded := codecs(Precision.UINT8).decode(lane.value.resize(8)) }
          is(Precision.INT16)   { decoded := codecs(Precision.INT16).decode(lane.value.resize(16)) }
          is(Precision.UINT16)  { decoded := codecs(Precision.UINT16).decode(lane.value.resize(16)) }
          is(Precision.INT32)   { decoded := codecs(Precision.INT32).decode(lane.value.resize(32)) }
          is(Precision.UINT32)  { decoded := codecs(Precision.UINT32).decode(lane.value.resize(32)) }
          is(Precision.INT64)   { decoded := codecs(Precision.INT64).decode(lane.value.resize(64)) }
          is(Precision.UINT64)  { decoded := codecs(Precision.UINT64).decode(lane.value.resize(64)) }
        }
        decoded
      })
    }

    def encode(components: Vec[SIMDComponents], roundMode: RoundMode.E): SIMDVector = {
      val result = SIMDVector()
      for (i <- 0 until 16) {
        switch(components(i).precision) {
          is(Precision.FP8E4M3) { result.lanes(i) := codecs(Precision.FP8E4M3).encode(components(i), roundMode) }
          is(Precision.FP8E5M2) { result.lanes(i) := codecs(Precision.FP8E5M2).encode(components(i), roundMode) }
          is(Precision.FP16)    { result.lanes(i) := codecs(Precision.FP16).encode(components(i), roundMode) }
          is(Precision.BF16)    { result.lanes(i) := codecs(Precision.BF16).encode(components(i), roundMode) }
          is(Precision.TF32)    { result.lanes(i) := codecs(Precision.TF32).encode(components(i), roundMode) }
          is(Precision.FP32)    { result.lanes(i) := codecs(Precision.FP32).encode(components(i), roundMode) }
          is(Precision.FP64)    { result.lanes(i) := codecs(Precision.FP64).encode(components(i), roundMode) }
          is(Precision.INT8)    { result.lanes(i) := codecs(Precision.INT8).encode(components(i), roundMode) }
          is(Precision.UINT8)   { result.lanes(i) := codecs(Precision.UINT8).encode(components(i), roundMode) }
          is(Precision.INT16)   { result.lanes(i) := codecs(Precision.INT16).encode(components(i), roundMode) }
          is(Precision.UINT16)  { result.lanes(i) := codecs(Precision.UINT16).encode(components(i), roundMode) }
          is(Precision.INT32)   { result.lanes(i) := codecs(Precision.INT32).encode(components(i), roundMode) }
          is(Precision.UINT32)  { result.lanes(i) := codecs(Precision.UINT32).encode(components(i), roundMode) }
          is(Precision.INT64)   { result.lanes(i) := codecs(Precision.INT64).encode(components(i), roundMode) }
          is(Precision.UINT64)  { result.lanes(i) := codecs(Precision.UINT64).encode(components(i), roundMode) }
        }
      }
      result
    }
  }

  // Configuration constants
  case class Config() {
    val lanes = 16
    val maxWidth = 64
    val registerCount = 64
  }

  // FPU operation definition
  case class FpuOp() extends Bundle {
    val opcode = FpuOpcode()
    val src1, src2, src3 = RegIdx()
    val dst = RegIdx()
    val precision = Precision()
    val roundMode = RoundMode()
    val subNormMode = SubNormMode()
    val modifiers = Modifiers()
  }

  // Exception flags
  case class Exceptions() extends Bundle {
    val inexact, underflow, overflow, divByZero, subNormal, invalid, intDivByZero = Bool()
  }

  // Test vectors component
  case class FpuTestVectors() extends Component {
    val io = new Bundle {
      val clk = in(Bool())
      val rst = in(Bool())
      val inputVector = in(SIMDVector())
    }
    val cd = ClockDomain(io.clk, io.rst)
    val vectors = new Area {
      val fp32_1_0 = B"32'h3f800000"           // FP32 1.0
      val int32_255 = B"32'h000000ff"          // INT32 255
      val fp32_subnormal = B"32'h00800000"     // FP32 subnormal
      val fp32_quiet_nan = B"32'h7fc00000"     // FP32 quiet NaN
      val fp32_signaling_nan = B"32'h7f800001" // FP32 signaling NaN
      val fp32_neg_quiet_nan = B"32'hffc00000" // FP32 -quiet NaN
      val fp32_neg_signaling_nan = B"32'hff800001" // FP32 -signaling NaN
    }

    val decodedReg = Reg(Vec(SIMDComponents(), 16)) init(SIMDComponents().getZero)
    decodedReg := RegNext(io.inputVector.decode())
    val decodedOut = out Vec(SIMDComponents(), 16)
    decodedOut := io.inputVector.decode()
  }
}