package sbm

import ip.{DspAcc_i19_o27, DspAdder39b, ResultFifoCC_W432_D512}
import sbm.GEMM._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}

object GEMM{
  val nGroup = 2
  val nCascade = 16
  val nBlock = nCascade/2
  val dspAccWidth = 19 //log2Up(nCascade*127*127) + 1
  val resultAccWidth = 27
  val cmdBurstWidth = log2Up(4096/nCascade)
  val cmdQueueDepth = 4
  val resultQueueDepth = 512
  val maBlocksNum = 128
  val dspPreAdderShift = 17

  val maRamSize = 32 KiB
  val mbRamSize = 8 KiB
  val maRamDepth = (maRamSize / 8).toInt
  val mbRamDepth = (mbRamSize / 8).toInt
  val lineAddressWidth = log2Up(maRamDepth*nBlock) + 1

  def flowPipe[T<:Data](p:Flow[T], n:Int): Flow[T] ={
    (0 until n).foldLeft(p)((pre, _) => pre.stage())
  }
  def streamPipe[T<:Data](p:Stream[T], n:Int): Stream[T] = {
    (0 until n).foldLeft(p)((pre, _) => pre.stage())
  }

  def getAxi4MaConfig: Axi4Config ={
    Axi4Config(
      addressWidth = lineAddressWidth + log2Up(nGroup) + 3,
      dataWidth = 64,
      idWidth = 0,
      useResp = false,
      useQos = false,
      useLock = false,
      useProt = false,
      useCache = false,
      useSize = false,
      useRegion = false,
      useStrb = false
    )
  }
}

case class GemmWrite(addressWidth:Int, dataWidth:Int) extends Bundle {
  val address = UInt(addressWidth bits)
  val data = Bits(dataWidth bits)
}

object WriteFlow{
  def apply(addressWidth:Int, dataWidth:Int) = Flow(GemmWrite(addressWidth, dataWidth))
}

object VSInt{
  def apply(width:Int, len:Int) = Vec(SInt(width bits), len)
  def op(m0:Vec[SInt], m1:Vec[SInt])(f:(SInt, SInt)=>SInt):Vec[SInt]= {
    val that = m0.zip(m1).map(z => f(z._1, z._2))
    Vec(that)
  }
}

class GemmWriteDeMux extends Component {

  val io = new Bundle {
    val write = slave(WriteFlow(lineAddressWidth, 64))
    val writeNext = master(WriteFlow(lineAddressWidth, 64)).allowPruning()
    val aWrite = master(WriteFlow(log2Up(maRamDepth), 64))
    val bWrite = master(WriteFlow(log2Up(mbRamDepth), 64))
    val blockId = in UInt(log2Up(nBlock) bits)
  }

  val writeStage = io.write.stage()
  val groupIdRange = log2Up(nBlock) - 1 downto 0
  val blockHit = io.blockId===writeStage.address(groupIdRange)

  val writeTake = writeStage.takeWhen(blockHit)
  io.writeNext << writeStage.throwWhen(blockHit).allowPruning()

  io.aWrite << writeTake.throwWhen(writeTake.address.msb).translateWith{
    val ret = cloneOf(io.aWrite.payload)
    ret.address := (writeTake.address >> log2Up(nBlock)).resized
    ret.data := writeTake.data
    ret
  }
  io.bWrite << writeTake.takeWhen(writeTake.address.msb).translateWith{
    val ret = cloneOf(io.bWrite.payload)
    ret.address := (writeTake.address >> log2Up(nBlock)).resized
    ret.data := writeTake.data
    ret
  }
}

object GemmBus {
  def apply() = new GemmBus
  def writeDelay = nBlock + 1
}

class GemmBus extends Component {
  val io = new Bundle{
    val write = slave(WriteFlow(lineAddressWidth, 64))
    val aWrite = Vec(master(WriteFlow(log2Up(maRamDepth), 64)), nCascade/2)
    val bWrite = Vec(master(WriteFlow(log2Up(mbRamDepth), 64)), nCascade/2)
  }

  val writeDeMux = Seq.fill(nBlock)(new GemmWriteDeMux)

  writeDeMux.foldLeft(io.write){(w, de) =>
    de.io.write <-< w
    de.io.writeNext
  }.allowPruning()

  writeDeMux.zip(io.aWrite).foreach(z => z._1.io.aWrite >-> z._2)
  writeDeMux.zip(io.bWrite).foreach(z => z._1.io.bWrite >-> z._2)
  writeDeMux.zipWithIndex.foreach(z => z._1.io.blockId := z._2)
}

case class VPort(addrWidth:Int) extends Bundle with IMasterSlave {
  val addr = UInt(addrWidth bits)
  val v = VSInt(8, 8)

  override def asMaster() = {
    out(addr)
    in(v)
  }
}

class GemmAxiBus extends Component {
  val io = new Bundle{
    val axi = slave(Axi4(axiSlaveConfig))
    val writes = Vec(master(WriteFlow(lineAddressWidth, 64)), nGroup)
  }

  val axi4WriteOnlyToMatWrite = new Area {
    val axi = io.axi.toWriteOnly(idleOthers = true)
    val (aw, awForAck) = StreamFork2(axi.aw.unburstify.stage())
    val w = axi.w.stage()

    val writeFlow = StreamJoin(aw, w).translateWith{
      val ret = WriteFlow(axi.config.addressWidth, 64).payload
      ret.address := aw.addr
      ret.data := w.data
      ret
    }.toFlow

    val axiB =  awForAck.translateWith{
      val ret = cloneOf(axi.b.payload)
      ret.assignUnassignedByName(awForAck.fragment)
      ret.resp := Axi4.resp.OKAY
      ret
    }.takeWhen(awForAck.isLast)

    axi.b </< streamPipe(axiB, 10)
  }

  val decodeArea = new Area{
    import axi4WriteOnlyToMatWrite.writeFlow

    val lineAddressRange = 17 downto 3
    val groupSelRange = 19 downto 18
    val isMb = writeFlow.address(20)
    val lineAddress = (isMb ## writeFlow.address(lineAddressRange)).asUInt

    for(groupId <- 0 until nGroup){
      val groupHit = writeFlow.address(groupSelRange)===groupId || isMb
      io.writes(groupId) << writeFlow.takeWhen(groupHit)
        .translateWith{
          val ret = cloneOf(io.writes.head.payload)
          ret.address := lineAddress
          ret.data := writeFlow.data
          ret
        }
    }
  }
}

case class MBank(size:BigInt, fastClockDomain: ClockDomain) extends Component {
  val depth = size / 8
  val io = new Bundle {
    val read = slave(VPort(log2Up(depth)))
    val write = slave(WriteFlow(log2Up(depth), 64))
  }

  val ram = Mem(Bits(64 bits), depth)
  ram.write(
    address = io.write.address,
    data = io.write.data,
    enable = io.write.valid
  )

  val _ = new ClockingArea(fastClockDomain){
    io.read.v := RegNext(Vec(
      ram.readSync(io.read.addr, clockCrossing = true)
        .subdivideIn(8 bits)
        .map(_.asSInt)
    ))
  }
}

class DspMultiplex extends Component {

  val io = new Bundle{
    val src0 = in(VSInt(8, 2))
    val src1 = in(SInt(8 bits))
    val pIn = in(SInt(34 bits))
    val pOut = out(SInt(34 bits))
  }

  def getDelay = 4

  val stage0 = new Area {
    val A = RegNext(io.src0(1)<<dspPreAdderShift) // 左移17 [16:0]=[0...0]
    val D = RegNext(io.src0(0).resize(dspPreAdderShift+8)) //符号位扩展，位宽为25bits
    val B = RegNext(io.src1) //signed 8 bits
  }

  val stage1 = new Area {
    val AD = RegNext(stage0.A + stage0.D)
    val B = RegNext(stage0.B)
  }

  val M =  RegNext(stage1.AD * stage1.B)

  io.pOut := RegNext(M + io.pIn)
}

object Opu{
  def apply() = new Opu
  def pOutDelay = 5
  def pInDelay = pOutDelay - 1
}

class Opu extends Component{
  val io = new Bundle{
    val va, vb = in(VSInt(8, 4))
    val pIn = in(VSInt(34, 8))
    val pOut = out(VSInt(34, 8))
  }

  val vbFanout = Seq.fill(2)(RegNext(io.vb))
  val vaFanout = Seq.fill(2)(RegNext(io.va))

  for(i <- 0 until 8){
    val bi0 = (i%2)*2
    val bi1 = bi0 + 1
    val ai  = i/2
    val dsp = (new DspMultiplex).setName(s"DSP_a${ai}_b$bi1$bi0")
    dsp.io.src0(0) := vbFanout(i%2)(bi0)
    dsp.io.src0(1) := vbFanout(i%2)(bi1)
    dsp.io.src1 := vaFanout(i%2)(ai)
    dsp.io.pIn := io.pIn(i)
    io.pOut(i) := dsp.io.pOut
  }
}

object OpuBank {
  def apply() = new OpuBank
  def vDelay = 2
  def pOutDelay = Opu.pOutDelay + vDelay + 1
  def cmdPipe = 2
}

class OpuBank extends Component{
  val io = new Bundle {
    val cmdIn = slave(Flow(Fragment(new GemmCmdBase)))
    val cmdOut = master(Flow(Fragment(new GemmCmdBase)))
    val vaPort = master(VPort(log2Up(maRamDepth)))
    val vbPort = master(VPort(log2Up(mbRamDepth)))
    val pOut = out(VSInt(34, 8))
  }

  val cmdStage = io.cmdIn.stage()
  io.cmdOut <-< cmdStage

  io.vaPort.addr := cmdStage.maAddr
  io.vbPort.addr := cmdStage.mbAddr

  def vDivide(v:Vec[SInt]):Seq[Vec[SInt]] = {
    Seq(Vec(v.take(4)), RegNext(Vec(v.drop(4)))) //take()用于序列中获取前n个元素,drop丢弃前n个元素
  }
  val vaSeq = vDivide(io.vaPort.v) //读取数据位宽为64bits,与BankA中每行的位宽一致
  val vbSeq = vDivide(io.vbPort.v) //读取数据位宽为64bits,与BankB中每行的位宽一致

  val opu = Seq.fill(2)(Opu())
  opu.zip(vaSeq).foreach(z => z._1.io.va := z._2)
  opu.zip(vbSeq).foreach(z => z._1.io.vb := z._2)

  val zero:Vec[SInt] = VSInt(34, 8).getZero
  io.pOut := opu.foldLeft(zero){(acc, o)=>
    o.io.pIn := acc
    o.io.pOut
  }
}

class GemmCmdBase extends Bundle {
  val maAddr = UInt(log2Up(maRamDepth) bits)
  val mbAddr = UInt(log2Up(mbRamDepth) bits)
}

class GemmCmd extends GemmCmdBase {
  val len = UInt(cmdBurstWidth bits)
}

object AddBank{
  def apply(useDsp:Boolean) = new AddBank(useDsp)
  def totalDelay = 4
  def pipeDelay = totalDelay - 2
}

class AddBank(useDsp:Boolean) extends Component{
  val io = new Bundle{
    val bOddRecover = in(Vec(Bool(), 8))
    val a = in(VSInt(dspAccWidth, 16))
    val b = in(VSInt(17, 16))
    val s = out(VSInt(dspAccWidth, 16))
  }

  val s = cloneOf(io.s)

  if(useDsp){
    val dspAdder = (0 until 8).map { i =>
      val a = new DspAdder39b
      a.setName(s"dspAdder_$i")
    }
    val a = io.a
    val b = io.b.map(_.resize(19))
    val zeroPadding = B"0"
    for((da, i) <- dspAdder.zipWithIndex){
      da.io.A := (a(i*2) ## zeroPadding ## a(i*2 + 1)).asSInt
      da.io.B := (b(i*2) ## zeroPadding ## b(i*2 + 1)).asSInt
      da.io.C_IN := io.bOddRecover(i)
      s(i*2)   := da.io.S(38 downto 20)
      s(i*2+1) := da.io.S(18 downto 0)
    }
  }else{
    s.setAsReg()
    for((s, i) <- s.zipWithIndex){
      val aReg = RegNext(io.a(i))
      val bReg = RegNext(io.b(i))
      val sumAB = aReg + bReg
      if(i%2==0){
        s := sumAB
      }else{
        val cInReg = RegNext(io.bOddRecover(i/2)).setName(s"cin_$i")
        when(cInReg) {
          s := sumAB + 1
        }otherwise{
          s := sumAB
        }
      }
    }
  }

  io.s := Delay(s, AddBank.pipeDelay)
}

class CmdUnburstify extends Component {
  val io = new Bundle {
    val cmdIn = slave(Stream(new GemmCmd))
    val cmdOut = master(Flow(Fragment(new GemmCmdBase)))
  }

  val cmdIn = io.cmdIn.pipelined(StreamPipe.FULL)
  val burstCounter = Counter(cmdIn.len.getWidth bits, cmdIn.valid)
  cmdIn.ready := False
  when(burstCounter.value===cmdIn.len){
    burstCounter.clear()
    cmdIn.ready := True
  }

  val ret = cloneOf(io.cmdOut)
  ret.valid := cmdIn.valid
  ret.maAddr := cmdIn.maAddr + burstCounter
  ret.mbAddr := cmdIn.mbAddr + burstCounter
  ret.last := burstCounter.willClear
  ret.fragment.assignUnassignedByName(cmdIn.payload)
  io.cmdOut <-< ret
}

class GemmAcc extends Component{
  val io = new Bundle {
    val mIn = slave(Flow(Fragment(VSInt(dspAccWidth, 16))))
    val mOut = master(Flow(VSInt(resultAccWidth, 16)))
  }

  val dspAcc = (0 until 16).map{i=>
    (new DspAcc_i19_o27).setName(s"dspAcc_$i")
  }

  val bypass = io.mIn.isFirst
  val result = dspAcc.zip(io.mIn.fragment).map{z =>
    z._1.io.B := z._2.resized
    z._1.io.BYPASS := bypass
    z._1.io.Q
  }

  io.mOut << io.mIn.takeWhen(io.mIn.isLast).stage().stage().translateWith(Vec(result))
}

class GemmLine extends Component {
  val io = new Bundle {
    val cmd = slave(Stream(new GemmCmd))
    val vaPort = Vec(master(VPort(log2Up(maRamDepth))), nBlock)
    val vbPort = Vec(master(VPort(log2Up(mbRamDepth))), nBlock)
    val result = master(Flow(VSInt(resultAccWidth, 16)))
  }

  val cmdUnburstify = new CmdUnburstify
  cmdUnburstify.io.cmdIn << io.cmd

  val opuBank = (0 until nCascade/2).map { i =>
    OpuBank().setName(s"opu_bank_$i")
  }

  val addBank = Seq(AddBank(useDsp = false)) ++
    Seq.fill(nCascade/2-1)(AddBank(useDsp = true))

  opuBank.map(_.io.vaPort).zip(io.vaPort).foreach(z => z._1 <> z._2)
  opuBank.map(_.io.vbPort).zip(io.vbPort).foreach(z => z._1 <> z._2)

  val zero:Vec[SInt] = VSInt(dspAccWidth, 16).getZero
  val lineResult = opuBank.zip(addBank).foldLeft(zero){(acc, z)=>
    val opuP = z._1.io.pOut
    val addB = z._2
    addB.io.a := acc
    addB.io.b := Vec(opuP.flatMap{p =>
      Seq(p(16 downto 0), p(33 downto 17))
    })
    addB.io.bOddRecover := Vec(opuP.map(_(16)))
    addB.io.s
  }

  val cmdLast = opuBank.foldLeft(cmdUnburstify.io.cmdOut){
    (cmd, opuB)=>
      opuB.io.cmdIn << cmd
      flowPipe(opuB.io.cmdOut, AddBank.totalDelay - OpuBank.cmdPipe)
  }

  val cmdLastDelay = flowPipe(cmdLast, OpuBank.pOutDelay)
  val accCmd = cmdLastDelay.translateWith{
    val ret = Fragment(lineResult)
    ret.last := cmdLastDelay.last
    ret.fragment := lineResult
    ret
  }

  val gemmAcc = new GemmAcc
  gemmAcc.io.mIn << accCmd
  gemmAcc.io.mOut >> io.result
}

class ResultFifo(val popClock:ClockDomain, val pushClock:ClockDomain) extends Component{
  val io = new Bundle {
    val push = slave(Flow(VSInt(resultAccWidth, 16)))
    val pop = master(Stream(VSInt(resultAccWidth, 16)))
  }

  val fifo = new ResultFifoCC_W432_D512(popClock, pushClock)
  fifo.io.din := io.push.payload.asBits
  fifo.io.wr_en := io.push.valid

  val _ = new ClockingArea(popClock){
    val ready = io.pop.ready || !io.pop.valid
    val fifoValid0 = RegNextWhen(ready && !fifo.io.empty, ready, False)
    val fifoValid1 = RegNext(fifoValid0 && ready, False)
    val readOverflow = fifoValid1 && !io.pop.ready
    val payloadHold = RegNextWhen(fifo.io.dout, readOverflow)
    val validHold = RegInit(False) clearWhen io.pop.ready setWhen readOverflow

    io.pop.valid := validHold || fifoValid1
    io.pop.payload.assignFromBits(
      Mux(validHold, payloadHold, fifo.io.dout)
    )
    fifo.io.rd_en := ready
  }
}

class GemmBlock(val fastClockDomain:ClockDomain,
                val ctrlClockDomain:ClockDomain) extends Component{

  val io = new Bundle {
    val cmd = slave(Stream(new GemmCmd))
    val write = slave(WriteFlow(lineAddressWidth, 64))
    val result = master(Stream(VSInt(resultAccWidth, 16)))
  }

  val cmdQueue = StreamFifoCC(cloneOf(io.cmd.payload), cmdQueueDepth, pushClock = ctrlClockDomain, popClock = fastClockDomain)
  val gemmLine = fastClockDomain(new GemmLine)
  cmdQueue.io.push << io.cmd
  cmdQueue.io.pop  >> gemmLine.io.cmd

  val maBanks = (0 until nBlock).map{ i =>
    MBank(maRamSize, fastClockDomain).setName(s"maBank_$i")
  }
  val mbBanks = (0 until nBlock).map{ i =>
    MBank(mbRamSize, fastClockDomain).setName(s"mbBank_$i")
  }

  val gemmBus = new GemmBus
  gemmBus.io.write << io.write
  gemmBus.io.aWrite.zip(maBanks).foreach(z => z._2.io.write << z._1)
  gemmBus.io.bWrite.zip(mbBanks).foreach(z => z._2.io.write << z._1)

  gemmLine.io.vaPort.zip(maBanks).foreach(z => z._2.io.read <> z._1)
  gemmLine.io.vbPort.zip(mbBanks).foreach(z => z._2.io.read <> z._1)

  val resultQueue = new ResultFifo(pushClock = fastClockDomain, popClock = clockDomain)
  resultQueue.io.push << gemmLine.io.result
  resultQueue.io.pop  >> io.result
}

case class GemmCtrl() extends GemmCmd {
  val maNum = UInt(log2Up(maBlocksNum) bits)
}

class ResultMatCollector(nGroup:Int) extends Component {

  val io = new Bundle {
    val resultSrc = Vec(slave(Stream(VSInt(resultAccWidth, 16))), nGroup)
    val output = master(Stream(VSInt(resultAccWidth, 16)))
  }

  val srcPiped = io.resultSrc//.map(_.pipelined(StreamPipe.FULL))

  val selCounter = Counter(nGroup, io.output.fire)

  io.output << StreamMux(selCounter.value, srcPiped)
}

class GemmCtrlUnburstify extends Component{
  val cmdQueueDepth = 16

  val io = new Bundle{
    val ctrl = slave(Stream(GemmCtrl()))
    val cmdQueueOccupancy = out UInt(log2Up(cmdQueueDepth)+1 bits)
    val cmdOut = Vec(master(Stream(new GemmCmd)), nGroup)
  }

  val cmdQ = StreamFifo(io.ctrl.payload, cmdQueueDepth)
  cmdQ.io.push << io.ctrl
  io.cmdQueueOccupancy := cmdQ.io.occupancy
  val cmdQueued = cmdQ.io.pop
  val cmdUnburst = Stream(new GemmCmd)
  val burstCounter = Counter(log2Up(maBlocksNum) bits, cmdUnburst.fire)
  val maAddrAcc = Reg(cloneOf(io.ctrl.maAddr)) init 0
  cmdQueued.ready := False
  cmdUnburst.valid := cmdQueued.valid
  when(burstCounter===cmdQueued.maNum && burstCounter.willIncrement){
    burstCounter.clear()
    maAddrAcc := 0
    cmdQueued.ready := cmdUnburst.ready
  } elsewhen cmdUnburst.fire{
    maAddrAcc := maAddrAcc + cmdQueued.len + 1
  }

  cmdUnburst.maAddr := maAddrAcc + cmdQueued.maAddr
  cmdUnburst.payload.assignUnassignedByName(cmdQueued.payload)

  val cmdOut = cloneOf(io.cmdOut)
  StreamFork(cmdUnburst, nGroup).zip(cmdOut)
    .foreach{ case(cmd, o) =>
      cmd >> o
    }

  cmdOut.zip(io.cmdOut).foreach(z => z._1.pipelined(StreamPipe.FULL).pipelined(StreamPipe.FULL) >> z._2)
}

class GemmGroup(val fastClockDomain:ClockDomain,
                val ctrlClockDomain:ClockDomain) extends Component {

  val io = new Bundle {
    val ctrl = slave(Stream(GemmCtrl()))
    val bus  = slave(Axi4(axiSlaveConfig))
    val result = master(Stream(VSInt(resultAccWidth, 16)))
  }

  val ctrlBurst = ctrlClockDomain(new GemmCtrlUnburstify)
  val gemmBlock = Seq.fill(nGroup)(new GemmBlock(fastClockDomain, ctrlClockDomain))
  val gemmAxiBus = new GemmAxiBus
  val resultMatArbiter = new ResultMatCollector(nGroup)

  ctrlBurst.io.ctrl << io.ctrl
  ctrlBurst.io.cmdOut.zip(gemmBlock).foreach(z => z._1 >> z._2.io.cmd)

  gemmAxiBus.io.axi <> io.bus
  gemmAxiBus.io.writes.zip(gemmBlock).foreach(z => z._1 <> z._2.io.write)

  resultMatArbiter.io.resultSrc.zip(gemmBlock).foreach(z => z._1 << z._2.io.result)
  resultMatArbiter.io.output >> io.result
}

class ImplInst extends Component {
  val fast = ClockDomain.external("fast", config = MySpinalConfig.defaultConfigForClockDomains)
  val ctrl = ClockDomain.external("ctrl", config = MySpinalConfig.defaultConfigForClockDomains)
  val inst = new GemmGroup(fast, ctrl)
  inst.io.bus.setIdle()
  inst.io.ctrl.setIdle()
  inst.io.result.setBlocked()
  inst.addAttribute("keep_hierarchy", "yes")
}

object GEMMImple extends App{
  def fast = ClockDomain.external("fast")
  def ctrl = ClockDomain.external("ctrl")
  ImplConfigX.generateVerilog(new DspAcc_i19_o27).printPruned()
}





