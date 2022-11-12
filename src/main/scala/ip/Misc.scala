package ip

import spinal.core._
import sbm.GEMM.{dspAccWidth, resultAccWidth}

class DspAdder39b extends BlackBox {
  // do c = a + b + cin
  val io = new Bundle{
    val CLK = in Bool()
    val C_IN = in Bool()
    val A, B = in SInt(39 bits)
    val S = out SInt(39 bits)
  }
  noIoPrefix()
  mapCurrentClockDomain(io.CLK)
}

class DspAcc_i19_o27 extends BlackBox {
  val io = new Bundle {
    val CLK = in Bool()
    val B = in SInt(dspAccWidth bits)
    val BYPASS = in Bool()
    val Q = out SInt(resultAccWidth bits)
  }
  noIoPrefix()
  mapCurrentClockDomain(io.CLK)
}

class ResultFifoCC_W432_D512(popClock:ClockDomain, pushClock:ClockDomain) extends BlackBox {
  val io = new Bundle {
    val wr_clk, wr_rst = in Bool()
    val rd_clk, rd_rst = in Bool()
    val din = in Bits(432 bits)
    val wr_en, rd_en = in Bool()
    val dout = out Bits(432 bits)
    val full = out Bool()
    val empty = out Bool()
  }
  noIoPrefix()
  mapClockDomain(pushClock, clock = io.wr_clk, reset = io.wr_rst)
  mapClockDomain(popClock, clock = io.rd_clk, reset = io.rd_rst)
}

class PLL extends BlackBox{
  val io = new Bundle{
    val clk_in   = in Bool()
    val resetn = in Bool()
    val clk_fast = out Bool()
    val locked = out Bool()
  }
  noIoPrefix()
}

class ResidualFifo_W64_D1024 extends BlackBox {
  val io = new Bundle {
  }
}