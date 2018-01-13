package templates

import util._
import chisel3._
import chisel3.util._
import ops._
import fringe._
import chisel3.util.MuxLookup

import scala.collection.mutable.HashMap

sealed trait BankingMode
object DiagonalMemory extends BankingMode
object BankedMemory extends BankingMode

class flatW(val a: Int, val w: Int) extends Bundle {
  val addr = UInt(a.W)
  val data = UInt(w.W)
  val en = Bool()

  override def cloneType = (new flatW(a, w)).asInstanceOf[this.type] // See chisel3 bug 358
}
class flatR(val a:Int, val w: Int) extends Bundle {
  val addr = UInt(a.W)
  val en = Bool()

  override def cloneType = (new flatR(a, w)).asInstanceOf[this.type] // See chisel3 bug 358
}
class multidimW(val N: Int, val dims: List[Int], val w: Int) extends Bundle {
  assert(N == dims.length)
  // val addr = Vec(N, UInt(32.W))
  val addr = HVec.tabulate(N){i => UInt((Utils.log2Up(dims(i))).W)}
  // val addr = dims.map{d => UInt((Utils.log2Up(d)).W)}
  val data = UInt(w.W)
  val en = Bool()

  override def cloneType = (new multidimW(N, dims, w)).asInstanceOf[this.type] // See chisel3 bug 358
}
class multidimR(val N: Int, val dims: List[Int], val w: Int) extends Bundle {
  assert(N == dims.length)
  // val addr = Vec(N, UInt(32.W))
  val addr = HVec.tabulate(N){i => UInt((Utils.log2Up(dims(i))).W)}
  // val addr = dims.map{d => UInt((Utils.log2Up(d)).W)}
  val en = Bool()
  
  override def cloneType = (new multidimR(N, dims, w)).asInstanceOf[this.type] // See chisel3 bug 358
}

class R_Info(val ofs_width:Int, val bank_width:List[Int]) extends Bundle {
  val banks = HVec.tabulate(bank_width.length){i => UInt(bank_width(i).W)}
  val ofs = UInt(ofs_width.W)
  val en = Bool()

  override def cloneType = (new R_Info(ofs_width, bank_width)).asInstanceOf[this.type] // See chisel3 bug 358
}

class W_Info(val ofs_width:Int, val bank_width:List[Int], val data_width:Int) extends Bundle {
  val banks = HVec.tabulate(bank_width.length){i => UInt(bank_width(i).W)}
  val ofs = UInt(ofs_width.W)
  val data = UInt(data_width.W)
  val en = Bool()

  override def cloneType = (new W_Info(ofs_width, bank_width, data_width)).asInstanceOf[this.type] // See chisel3 bug 358
}

class Mem1D(val size: Int, bitWidth: Int, syncMem: Boolean = false) extends Module { // Unbanked, inner 1D mem
  def this(size: Int) = this(size, 32)

  val addrWidth = Utils.log2Up(size)

  val io = IO( new Bundle {
    val r = Input(new R_Info(32, List(1)))
    val rMask = Input(Bool())
    val w = Input(new W_Info(32, List(1), bitWidth))
    val wMask = Input(Bool())
    val flow = Input(Bool())
    val output = new Bundle {
      val data  = Output(UInt(bitWidth.W))
    }
    val debug = new Bundle {
      val invalidRAddr = Output(Bool())
      val invalidWAddr = Output(Bool())
      val rwOn = Output(Bool())
      val error = Output(Bool())
      // val addrProbe = Output(UInt(bitWidth.W))
    }
  })

  // We can do better than MaxJ by forcing mems to be single-ported since
  //   we know how to properly schedule reads and writes
  val wInBound = io.w.ofs < (size).U
  val rInBound = io.r.ofs < (size).U

  if (syncMem) {
    if (size <= Utils.SramThreshold) {
      val m = (0 until size).map{ i =>
        val reg = RegInit(0.U(bitWidth.W))
        reg := Mux(io.w.en & (io.w.ofs === i.U(addrWidth.W)), io.w.data, reg)
        (i.U(addrWidth.W) -> reg)
      }
      val radder = Utils.getRetimed(io.r.ofs,1)
      io.output.data := MuxLookup(radder, 0.U(bitWidth.W), m)
    } else {
      val m = Module(new fringe.SRAM(UInt(bitWidth.W), size))
      m.io.raddr     := io.r.ofs
      m.io.waddr     := io.w.ofs
      m.io.wen       := io.w.en & wInBound & io.wMask
      m.io.wdata     := io.w.data
      m.io.flow      := io.flow
      io.output.data := m.io.rdata
    }
  } else {
    if (size <= Utils.SramThreshold) {
      val m = (0 until size).map{ i =>
        val reg = RegInit(0.U(bitWidth.W))
        reg := Mux(io.w.en & io.wMask & (io.w.ofs === i.U(addrWidth.W)), io.w.data, reg)
        (i.U(addrWidth.W) -> reg)
      }
      io.output.data := MuxLookup(io.r.ofs, 0.U(bitWidth.W), m)
    } else {
      val m = Mem(size, UInt(bitWidth.W) /*, seqRead = true deprecated? */)
      when (io.w.en & io.wMask & wInBound) {m(io.w.ofs) := io.w.data}
      io.output.data := m(io.r.ofs)
    }
  }

  if (scala.util.Properties.envOrElse("RUNNING_REGRESSION", "0") == "1") {
    io.debug.invalidRAddr := ~rInBound
    io.debug.invalidWAddr := ~wInBound
    io.debug.rwOn := io.w.en & io.r.en & io.wMask & io.rMask
    io.debug.error := ~rInBound | ~wInBound | (io.w.en & io.r.en & io.wMask & io.rMask)
    // io.debug.addrProbe := m(0.U)
  }

}

// class MemND(val dims: List[Int], bitWidth: Int = 32, syncMem: Boolean = false) extends Module { 
//   val depth = dims.reduce{_*_} // Size of memory
//   val N = dims.length // Number of dimensions
//   val addrWidth = dims.map{Utils.log2Up(_)}.max

//   val io = IO( new Bundle {
//     val w = Input(new multidimW(N, dims, bitWidth))
//     val wMask = Input(Bool())
//     val r = Input(new multidimR(N, dims, bitWidth))
//     val rMask = Input(Bool())
//     val flow = Input(Bool())
//     val output = new Bundle {
//       val data  = Output(UInt(bitWidth.W))
//     }
//     val debug = new Bundle {
//       val invalidRAddr = Output(Bool())
//       val invalidWAddr = Output(Bool())
//       val rwOn = Output(Bool())
//       val error = Output(Bool())
//     }
//   })

//   // Instantiate 1D mem
//   val m = Module(new Mem1D(depth, bitWidth, syncMem))

//   // Address flattening
//   m.io.w.addr := Utils.getRetimed(io.w.addr.zipWithIndex.map{ case (addr, i) =>
//     // FringeGlobals.bigIP.multiply(addr, (banks.drop(i).reduce{_.*-*(_,None)}/-/banks(i)).U, 0)
//    addr.*-*((dims.drop(i).reduce{_*_}/dims(i)).U, None)
//   }.reduce{_+_}, 0 max Utils.sramstore_latency - 1)
//   m.io.r.addr := Utils.getRetimedStream(io.r.addr.zipWithIndex.map{ case (addr, i) =>
//     // FringeGlobals.bigIP.multiply(addr, (dims.drop(i).reduce{_.*-*(_,None)}/dims(i)).U, 0)
//    addr.*-*((dims.drop(i).reduce{_*_}/dims(i)).U, None)
//   }.reduce{_+_}, 0 max {Utils.sramload_latency - 1}, io.flow) // Latency set to 2, give 1 cycle for bank to resolve

//   // Connect the other ports
//   m.io.w.data := Utils.getRetimed(io.w.data, 0 max Utils.sramstore_latency - 1)
//   m.io.w.en := Utils.getRetimed(io.w.en & io.wMask, 0 max Utils.sramstore_latency - 1)
//   m.io.r.en := Utils.getRetimedStream(io.r.en & io.rMask, 0 max {Utils.sramload_latency - 1}, io.flow) // Latency set to 2, give 1 cycle for bank to resolve
//   m.io.flow := io.flow
//   io.output.data := Utils.getRetimedStream(m.io.output.data, if (syncMem) 0 else {if (Utils.retime) 1 else 0}, io.flow)
//   if (scala.util.Properties.envOrElse("RUNNING_REGRESSION", "0") == "1") {
//     // Check if read/write is in bounds
//     val rInBound = io.r.addr.zip(dims).map { case (addr, bound) => addr < bound.U }.reduce{_&_}
//     val wInBound = io.w.addr.zip(dims).map { case (addr, bound) => addr < bound.U }.reduce{_&_}
//     io.debug.invalidWAddr := ~wInBound
//     io.debug.invalidRAddr := ~rInBound
//     io.debug.rwOn := io.w.en & io.wMask & io.r.en & io.rMask
//     io.debug.error := ~wInBound | ~rInBound | (io.w.en & io.r.en)
//   }
// }



/*
                            
                                                           __________             ___SRAM__
         _        _           _______                     |          |--bundleND-|   Mem1D |               
        | |------| |---------|       |                    |          |           |_________|                        
   IO(Vec(bundleSRAM))-------| Mux1H |-----bundleSRAM-----|   VAT    |--bundleND-|   Mem1D |    
        |_|------|_|---------|_______|                    |          |           |_________|                        
                               | | |                      |__________|--bundleND-|   Mem1D |               
                             stageEnables                                        |_________|
                                                                        
                                                                    
*/
class SRAM(val logicalDims: List[Int], val bitWidth: Int, 
           val banks: List[Int], val strides: List[Int], 
           val wPar: List[Int], val rPar: List[Int], val bankingMode: BankingMode, val syncMem: Boolean = false) extends Module { 

  // Overloaded construters
  // Tuple unpacker
  def this(tuple: (List[Int], Int, List[Int], List[Int], 
           List[Int], List[Int], BankingMode)) = this(tuple._1,tuple._2,tuple._3,tuple._4,tuple._5,tuple._6,tuple._7)
  // Bankmode-less
  def this(logicalDims: List[Int], bitWidth: Int, 
           banks: List[Int], strides: List[Int], 
           wPar: List[Int], rPar: List[Int]) = this(logicalDims, bitWidth, banks, strides, wPar, rPar, BankedMemory)
  // If 1D, spatial will make banks and strides scalars instead of lists
  def this(logicalDims: List[Int], bitWidth: Int, 
           banks: Int, strides: Int, 
           wPar: List[Int], rPar: List[Int]) = this(logicalDims, bitWidth, List(banks), List(strides), wPar, rPar, BankedMemory)

  val depth = logicalDims.reduce{_*_} // Size of memory
  val N = logicalDims.length // Number of dimensions
  val addrWidth = logicalDims.map{Utils.log2Up(_)}.max

  val io = IO( new Bundle {
    // TODO: w bundle gets forcefully generated as output in verilog
    //       so the only way to make it an input seems to flatten the
    //       Vec(numWriters, Vec(wPar, _)) to a 1D vector and then reconstruct it
    val w = Vec(wPar.reduce{_+_}, Input(new W_Info(32, List.fill(banks.length)(32), bitWidth)))
    val r = Vec(rPar.reduce{_+_},Input(new R_Info(32, List.fill(banks.length)(32)))) // TODO: Spatial allows only one reader per mem
    val flow = Vec(rPar.length, Input(Bool()))
    val output = new Bundle {
      val data  = Vec(rPar.reduce{_+_}, Output(UInt(bitWidth.W)))
    }
    val debug = new Bundle {
      val invalidRAddr = Output(Bool())
      val invalidWAddr = Output(Bool())
      val rwOn = Output(Bool())
      val readCollision = Output(Bool())
      val writeCollision = Output(Bool())
      val error = Output(Bool())
    }
  })

  // Get info on physical dims
  // TODO: Upcast dims to evenly bank
  val physicalDims = bankingMode match {
    // case DiagonalMemory => logicalDims.zipWithIndex.map { case (dim, i) => if (i == N - 1) math.ceil(dim.toDouble/banks.head).toInt else dim}
    case BankedMemory => math.ceil(logicalDims.product.toDouble / banks.product).toInt
  }
  val numMems = bankingMode match {
    case DiagonalMemory => banks.head
    case BankedMemory => banks.product
  }

  // Create list of (mem: Mem1D, coords: List[Int] <coordinates of bank>)
  val m = (0 until numMems).map{ i => 
    val mem = Module(new Mem1D(physicalDims, bitWidth, syncMem))
    val coords = banks.zipWithIndex.map{ case (b,j) => 
      i % (banks.drop(j).product) / banks.drop(j+1).product
    }
    (mem,coords)
  }

  m.foreach{ mem => 
    // Connect any W_Info input whose bank dims match this mem and whose enable is on
    val bundleSelect = io.w.map(_.banks).zip(io.w.map(_.en)).map{ case(bids, en) => 
      bids.zip(mem._2).map{case (b,coord) => b === coord.U}.reduce{_&&_} & en 
    }
    mem._1.io.wMask := bundleSelect.reduce{_|_} 
    mem._1.io.w := chisel3.util.PriorityMux(bundleSelect, io.w)
  }

  m.zipWithIndex.foreach{ case (mem, i) => 
    val bundleSelect = io.r.map(_.banks).zip(io.r.map(_.en)).map{ case(bids, en) => 
      bids.zip(mem._2).map{case (b,coord) => b === coord.U}.reduce{_&&_} & en 
    }
    mem._1.io.rMask := bundleSelect.reduce{_|_}
    mem._1.io.r := chisel3.util.PriorityMux(bundleSelect, io.r)
    mem._1.io.flow := io.flow.reduce{_&_} // TODO: Dangerous but probably works
  }

  // Connect read data to output
  io.output.data.zip(io.r.map(_.banks)).foreach { case (wire, id) => 
    val sel = m.map{ mem => 
      id.zip(mem._2).map{case (b, coord) => Utils.getRetimed(b,  Utils.sramload_latency) === coord.U}.reduce{_&&_}
    }
    val datas = m.map{ _._1.io.output.data }
    val d = chisel3.util.PriorityMux(sel, datas)
    wire := d
  }

  var wInUse = Array.fill(wPar.length) {false} // Array for tracking which wPar sections are in use
  def connectWPort(wBundle: Vec[W_Info], ports: List[Int]) {
    // Figure out which wPar section this wBundle fits in by finding first false index with same wPar
    val potentialFits = wPar.zipWithIndex.filter(_._1 == wBundle.length).map(_._2)
    val wId = potentialFits(potentialFits.map(wInUse(_)).indexWhere(_ == false))
    val port = ports(0) // Should never have more than 1 for SRAM
    // Get start index of this section
    val base = if (wId > 0) {wPar.take(wId).reduce{_+_}} else 0
    // Connect to wPar(wId) elements from base
    (0 until wBundle.length).foreach{ i => 
      io.w(base + i) := wBundle(i) 
    }
    // Set this section in use
    wInUse(wId) = true
  }

  var rId = 0
  var flowId = 0
  def connectRPort(rBundle: Vec[R_Info], port: Int): Int = {
    // Get start index of this section
    val base = rId
    // Connect to rPar(rId) elements from base
    (0 until rBundle.length).foreach{ i => 
      io.r(base + i) := rBundle(i) 
    }
    io.flow(flowId) := true.B
    flowId = flowId + 1
    rId = rId + rBundle.length
    base
  }

  def connectRPort(rBundle: Vec[R_Info], port: Int, flow: Bool): Int = {
    // Get start index of this section
    val base = rId
    // Connect to rPar(rId) elements from base
    (0 until rBundle.length).foreach{ i => 
      io.r(base + i) := rBundle(i) 
    }
    io.flow(flowId) := flow
    flowId = flowId + 1
    rId = rId + rBundle.length
    base
  }

  if (scala.util.Properties.envOrElse("RUNNING_REGRESSION", "0") == "1") { // Major hack until someone helps me include the sv file in Driver (https://groups.google.com/forum/#!topic/chisel-users/_wawG_guQgE)
    // Connect debug signals
    val wInBound = io.w.map{ v => v.ofs < logicalDims.reduce{_*_}.U}.reduce{_&_}
    val rInBound = io.r.map{ v => v.ofs < logicalDims.reduce{_*_}.U}.reduce{_&_}
    val writeOn = io.w.map{ v => v.en }
    val readOn = io.r.map{ v => v.en }
    val rwOn = writeOn.zip(readOn).map{ case(a,b) => a&b}.reduce{_|_}
    val rCollide = io.r.map(_.banks).zip( readOn).map{ case(id1,en1) => io.r.map(_.banks).zip( readOn).map{ case(id2,en2) => Mux((id1.zip(id2).map{case(a,b) => a===b}.reduce{_&&_}) & en1 & en2, 1.U, 0.U)}.reduce{_+_} }.reduce{_+_} !=  readOn.map{Mux(_, 1.U, 0.U)}.reduce{_+_}
    val wCollide = io.w.map(_.banks).zip(writeOn).map{ case(id1,en1) => io.w.map(_.banks).zip(writeOn).map{ case(id2,en2) => Mux((id1.zip(id2).map{case(a,b) => a===b}.reduce{_&&_}) & en1 & en2, 1.U, 0.U)}.reduce{_+_} }.reduce{_+_} != writeOn.map{Mux(_, 1.U, 0.U)}.reduce{_+_}
    io.debug.invalidWAddr := ~wInBound
    io.debug.invalidRAddr := ~rInBound
    io.debug.rwOn := rwOn
    io.debug.readCollision := rCollide
    io.debug.writeCollision := wCollide
    io.debug.error := ~wInBound | ~rInBound | rwOn | rCollide | wCollide
  }

}


class NBufSRAM(val logicalDims: List[Int], val numBufs: Int, val bitWidth: Int, 
           val banks: List[Int], val strides: List[Int], 
           val wPar: List[Int], val rPar: List[Int], 
           val wBundling: List[Int], val rBundling: List[Int], val bPar: List[Int], val bankingMode: BankingMode, val syncMem: Boolean = false) extends Module { 

  // Overloaded construters
  // Tuple unpacker
  def this(tuple: (List[Int], Int, Int, List[Int], List[Int], 
           List[Int], List[Int], List[Int], List[Int], List[Int], BankingMode)) = this(tuple._1,tuple._2,tuple._3,tuple._4,tuple._5,tuple._6,tuple._7,tuple._8,tuple._9,tuple._10,tuple._11)
  // Bankmode-less
  def this(logicalDims: List[Int], numBufs: Int, bitWidth: Int, 
           banks: List[Int], strides: List[Int], 
           wPar: List[Int], rPar: List[Int], 
           wBundling: List[Int], rBundling: List[Int], bPar: List[Int]) = this(logicalDims, numBufs, bitWidth, banks, strides, wPar, rPar, wBundling, rBundling, bPar, BankedMemory)
  // If 1D, spatial will make banks and strides scalars instead of lists
  def this(logicalDims: List[Int], numBufs: Int, bitWidth: Int, 
           banks: Int, strides: Int, 
           wPar: List[Int], rPar: List[Int], 
           wBundling: List[Int], rBundling: List[Int], bPar: List[Int]) = this(logicalDims, numBufs, bitWidth, List(banks), List(strides), wPar, rPar, wBundling, rBundling, bPar, BankedMemory)

  val depth = logicalDims.reduce{_*_} // Size of memory
  val N = logicalDims.length // Number of dimensions
  val addrWidth = logicalDims.map{Utils.log2Up(_)}.max

  val wHashmap = wPar.zip(wBundling).groupBy{_._2}
  val rHashmap = rPar.zip(rBundling).groupBy{_._2}
  val maxR = rHashmap.map{_._2.map{_._1}.reduce{_+_}}.max
  val io = IO( new Bundle {
    val sEn = Vec(numBufs, Input(Bool()))
    val sDone = Vec(numBufs, Input(Bool()))
    val w = Vec(wPar.reduce{_+_}, Input(new W_Info(32, List.fill(banks.length)(32), bitWidth)))
    val broadcast = Vec(bPar.reduce{_+_}, Input(new W_Info(32, List.fill(banks.length)(32), bitWidth)))
    val r = Vec(rPar.reduce{_+_},Input(new R_Info(32, List.fill(banks.length)(32)))) // TODO: Spatial allows only one reader per mem
    val flow = Vec(rPar.length, Input(Bool()))
    val output = new Bundle {
      val data  = Vec(numBufs*maxR, Output(UInt(bitWidth.W)))  
    }
    val debug = new Bundle {
      val invalidRAddr = Output(Bool())
      val invalidWAddr = Output(Bool())
      val rwOn = Output(Bool())
      val readCollision = Output(Bool())
      val writeCollision = Output(Bool())
      val error = Output(Bool())
    }
  })

  // // Chisel3 broke this on 3/24/2017...
  // val reconstructedOut = (0 until numBufs).map{ h =>
  //   Vec((0 until rPar).map {
  //     j => io.output.data(h*-*rPar + j)
  //   })
  // }

  // Get info on physical dims
  // TODO: Upcast dims to evenly bank
  val physicalDims = logicalDims.product / banks.product
  val numMems = banks.reduce{_*_}

  // Create physical mems
  val srams = (0 until numBufs).map{ i => Module(
    new SRAM(logicalDims,
            bitWidth, banks, strides, 
            List(wPar, bPar).flatten, List(maxR), bankingMode, syncMem)
  )}

  val sEn_latch = (0 until numBufs).map{i => Module(new SRFF())}
  val sDone_latch = (0 until numBufs).map{i => Module(new SRFF())}

  val swap = Wire(Bool())

  // Latch whether each buffer's stage is enabled and when they are done
  (0 until numBufs).foreach{ i => 
    sEn_latch(i).io.input.set := io.sEn(i) & ~io.sDone(i)
    sEn_latch(i).io.input.reset := Utils.getRetimed(swap,1)
    sEn_latch(i).io.input.asyn_reset := Utils.getRetimed(reset, 1)
    sDone_latch(i).io.input.set := io.sDone(i)
    sDone_latch(i).io.input.reset := Utils.getRetimed(swap,1)
    sDone_latch(i).io.input.asyn_reset := Utils.getRetimed(reset, 1)
  }
  val anyEnabled = sEn_latch.map{ en => en.io.output.data }.reduce{_|_}
  swap := Utils.risingEdge(sEn_latch.zip(sDone_latch).zipWithIndex.map{ case ((en, done), i) => en.io.output.data === (done.io.output.data || io.sDone(i)) }.reduce{_&_} & anyEnabled)

  val statesInW = wHashmap.map { t =>
    val c = Module(new NBufCtr(1,Some(t._1), Some(numBufs), 1+Utils.log2Up(numBufs)))
    c.io.input.enable := swap
    c.io.input.countUp := false.B
    (t._1 -> c)
  }
  val statesInR = (0 until numBufs).map{  i => 
    val c = Module(new NBufCtr(1,Some(i), Some(numBufs), 1+Utils.log2Up(numBufs)))
    c.io.input.enable := swap
    c.io.input.countUp := true.B
    c
  }

  val statesOut = (0 until numBufs).map{  i => 
    val c = Module(new NBufCtr(1,Some(i), Some(numBufs), 1+Utils.log2Up(numBufs)))
    c.io.input.enable := swap
    c.io.input.countUp := false.B
    c
  }

  srams.zipWithIndex.foreach{ case (f,i) => 
    wHashmap.foreach { t =>
      val pars = t._2.map{_._1}.reduce{_+_}
      val base = if (t._1 == 0) 0 else (0 until t._1).map{ii => wHashmap.getOrElse(ii, List((0,0))).map{_._1}.reduce{_+_}}.reduce{_+_}
      val wMask = Utils.getRetimed(statesInW(t._1).io.output.count === i.U, {if (Utils.retime) 1 else 0})
      (0 until pars).foreach{ k =>
        val masked_w = Wire(new W_Info(32, List.fill(banks.length)(32), bitWidth))
        masked_w.en := io.w(base+k).en & wMask
        masked_w.data := io.w(base+k).data
        masked_w.ofs := io.w(base+k).ofs
        (0 until banks.length).foreach{ j => masked_w.banks(j) := io.w(base+k).banks(j)}
        f.io.w(base+k) := masked_w
      }
    }
    // (0 until wPar.reduce{_+_}).foreach { k =>
    //   val masked_w = Wire(new multidimW(N, bitWidth))
    //   masked_w.en := io.w(k).en & wMask
    //   masked_w.data := io.w(k).data
    //   masked_w.ofs := io.w(k).ofs
    //   f.io.w(k) := masked_w
    // }
    (0 until bPar.reduce{_+_}).foreach {k =>
      f.io.w(wPar.reduce{_+_} + k) := io.broadcast(k)
    }

    var idx = 0 
    var idx_meaningful = 0 
    val rSel = (0 until numBufs).map{ a => Utils.getRetimed(statesInR(i).io.output.count === a.U, {if (Utils.retime) 1 else 0})}
    (0 until maxR).foreach {lane => // Technically only need per read and not per buf but oh well
      // Assemble buffet of read ports
      val buffet = (0 until numBufs).map {p => 
        val size = rHashmap.getOrElse(p, List((0,0))).map{_._1}.reduce{_+_}
        val base = if (p > 0) {(0 until p).map{ q =>
          rHashmap.getOrElse(q,List((0,0))).map{_._1}.reduce{_+_}
          }.reduce{_+_}
          } else {0}
        val dummy_r = Wire(new R_Info(32, List.fill(banks.length)(32)))
        dummy_r.en := false.B
        if (lane < size) {io.r(base + lane)} else dummy_r
      }
      f.io.r(lane) := chisel3.util.Mux1H(rSel, buffet)
    }
    f.io.flow(0) := io.flow.reduce{_&_}
  }

  (0 until numBufs).foreach {i =>
    val sel = (0 until numBufs).map{ a => Utils.getRetimed(statesOut(i).io.output.count === a.U, {if (Utils.retime) 1 else 0}) }
    (0 until maxR).foreach{ j => 
      io.output.data(i*-*maxR + j) := chisel3.util.Mux1H(sel, srams.map{f => f.io.output.data(j)})
    }
  }

  var wInUse = wHashmap.map{(_._1 -> 0)} // Tracket connect write lanes per port
  var bId = 0
  def connectWPort(wBundle: Vec[W_Info], ports: List[Int]) {
    if (ports.length == 1) {
      // Figure out which wPar section this wBundle fits in by finding first false index with same wPar
      val port = ports(0) 
      val wId = wInUse(port)
      val base = if (port == 0) wId else {(0 until port).map{i => wHashmap.getOrElse(i, List((0,0))).map{_._1}.reduce{_+_}}.reduce{_+_} + wId}
      // Get start index of this section
      (0 until wBundle.length).foreach{ i => 
        io.w(base + i) := wBundle(i) 
      }
      // Set this section in use
      wInUse += (port -> {wId + wBundle.length})
    } else { // broadcast
      (0 until wBundle.length).foreach{ i => 
        io.broadcast(bId + i) := wBundle(i) 
      }
      bId = bId + wBundle.length
    }
  }

  var rInUse = rHashmap.map{(_._1 -> 0)} // Tracking connect read lanes per port
  var flowId = 0
  def connectRPort(rBundle: Vec[R_Info], port: Int): Int = {
    // Figure out which rPar section this wBundle fits in by finding first false index with same rPar
    val rId = rInUse(port)
    // Get start index of this section
    val base = port *-* maxR + rId
    val packbase = if (port > 0) {
      (0 until port).map{p => 
        rHashmap.getOrElse(p, List((0,0))).map{_._1}.reduce{_+_}
      }.reduce{_+_}
    } else {0}
    io.flow(flowId) := true.B
    flowId = flowId + 1
    // Connect to rPar(rId) elements from base
    (0 until rBundle.length).foreach{ i => 
      io.r(packbase + rId + i) := rBundle(i) 
    }
    rInUse += (port -> {rId + rBundle.length})
    base
  }

  def connectRPort(rBundle: Vec[R_Info], port: Int, flow: Bool): Int = {
    // Figure out which rPar section this wBundle fits in by finding first false index with same rPar
    val rId = rInUse(port)
    // Get start index of this section
    val base = port *-* maxR + rId
    val packbase = if (port > 0) {
      (0 until port).map{p => 
        rHashmap.getOrElse(p, List((0,0))).map{_._1}.reduce{_+_}
      }.reduce{_+_}
    } else {0}
    io.flow(flowId) := flow
    flowId = flowId + 1
    // Connect to rPar(rId) elements from base
    (0 until rBundle.length).foreach{ i => 
      io.r(packbase + rId + i) := rBundle(i) 
    }
    rInUse += (port -> {rId + rBundle.length})
    base
  }

  def connectStageCtrl(done: Bool, en: Bool, ports: List[Int]) {
    ports.foreach{ port => 
      io.sEn(port) := en
      io.sDone(port) := done
    }
  }

  def connectUnwrittenPorts(ports: List[Int]) { // TODO: Remnant from maxj?
    // ports.foreach{ port => 
    //   io.input(port).enable := false.B
    // }
  }
 
  // def readTieDown(port: Int) { 
  //   (0 until numReaders).foreach {i => 
  //     io.rSel(port *-* numReaders + i) := false.B
  //   }
  // }

  def connectUntouchedPorts(ports: List[Int]) {
    ports.foreach{ port => 
      io.sEn(port) := false.B
      io.sDone(port) := false.B
    }
  }

  def connectDummyBroadcast() {
    (0 until bPar.reduce{_+_}).foreach { i =>
      io.broadcast(i).en := false.B
    }
  }



}



class NBufSRAMnoBcast(val logicalDims: List[Int], val numBufs: Int, val bitWidth: Int, 
           val banks: List[Int], val strides: List[Int], 
           val wPar: List[Int], val rPar: List[Int], 
           val wBundling: List[Int], val rBundling: List[Int], val bPar: List[Int], val bankingMode: BankingMode, val syncMem: Boolean = false) extends Module { 

  // Overloaded constructers
  // Tuple unpacker
  assert(bPar.reduce{_+_} == 0)
  def this(tuple: (List[Int], Int, Int, List[Int], List[Int], 
           List[Int], List[Int], List[Int], List[Int], List[Int], BankingMode)) = this(tuple._1,tuple._2,tuple._3,tuple._4,tuple._5,tuple._6,tuple._7,tuple._8,tuple._9,tuple._10,tuple._11)
  // Bankmode-less
  def this(logicalDims: List[Int], numBufs: Int, bitWidth: Int, 
           banks: List[Int], strides: List[Int], 
           wPar: List[Int], rPar: List[Int], 
           wBundling: List[Int], rBundling: List[Int], bPar: List[Int]) = this(logicalDims, numBufs, bitWidth, banks, strides, wPar, rPar, wBundling, rBundling, bPar, BankedMemory)
  // If 1D, spatial will make banks and strides scalars instead of lists
  def this(logicalDims: List[Int], numBufs: Int, bitWidth: Int, 
           banks: Int, strides: Int, 
           wPar: List[Int], rPar: List[Int], 
           wBundling: List[Int], rBundling: List[Int], bPar: List[Int]) = this(logicalDims, numBufs, bitWidth, List(banks), List(strides), wPar, rPar, wBundling, rBundling, bPar, BankedMemory)

  val depth = logicalDims.reduce{_*_} // Size of memory
  val N = logicalDims.length // Number of dimensions
  val addrWidth = logicalDims.map{Utils.log2Up(_)}.max

  val wHashmap = wPar.zip(wBundling).groupBy{_._2}
  val rHashmap = rPar.zip(rBundling).groupBy{_._2}
  val maxR = rHashmap.map{_._2.map{_._1}.reduce{_+_}}.max
  val io = IO( new Bundle {
    val sEn = Vec(numBufs, Input(Bool()))
    val sDone = Vec(numBufs, Input(Bool()))
    val w = Vec(wPar.reduce{_+_}, Input(new W_Info(32, List.fill(banks.length)(32), bitWidth)))
    val r = Vec(rPar.reduce{_+_},Input(new R_Info(32, List.fill(banks.length)(32)))) // TODO: Spatial allows only one reader per mem
    val flow = Vec(rPar.length, Input(Bool()))
    val output = new Bundle {
      val data  = Vec(numBufs *-* maxR, Output(UInt(bitWidth.W)))  
    }
    val debug = new Bundle {
      val invalidRAddr = Output(Bool())
      val invalidWAddr = Output(Bool())
      val rwOn = Output(Bool())
      val readCollision = Output(Bool())
      val writeCollision = Output(Bool())
      val error = Output(Bool())
    }
  })

  // // Chisel3 broke this on 3/24/2017...
  // val reconstructedOut = (0 until numBufs).map{ h =>
  //   Vec((0 until rPar).map {
  //     j => io.output.data(h*-*rPar + j)
  //   })
  // }

  // Get info on physical dims
  // TODO: Upcast dims to evenly bank
  val physicalDims = logicalDims.zip(banks).map { case (dim, b) => dim/b}
  val numMems = banks.reduce{_*_}

  // Create physical mems
  val srams = (0 until numBufs).map{ i => Module(
    new SRAM(logicalDims,
            bitWidth, banks, strides, 
            List(wPar, bPar).flatten, List(maxR), bankingMode, syncMem)
  )}

  val sEn_latch = (0 until numBufs).map{i => Module(new SRFF())}
  val sDone_latch = (0 until numBufs).map{i => Module(new SRFF())}

  val swap = Wire(Bool())

  // Latch whether each buffer's stage is enabled and when they are done
  (0 until numBufs).foreach{ i => 
    sEn_latch(i).io.input.set := io.sEn(i) & ~io.sDone(i)
    sEn_latch(i).io.input.reset := Utils.getRetimed(swap,1)
    sEn_latch(i).io.input.asyn_reset := Utils.getRetimed(reset, 1)
    sDone_latch(i).io.input.set := io.sDone(i)
    sDone_latch(i).io.input.reset := Utils.getRetimed(swap,1)
    sDone_latch(i).io.input.asyn_reset := Utils.getRetimed(reset, 1)
  }
  val anyEnabled = sEn_latch.map{ en => en.io.output.data }.reduce{_|_}
  swap := Utils.risingEdge(sEn_latch.zip(sDone_latch).zipWithIndex.map{ case ((en, done), i) => en.io.output.data === (done.io.output.data || io.sDone(i)) }.reduce{_&_} & anyEnabled)

  val statesInW = wHashmap.map { t =>
    val c = Module(new NBufCtr(1,Some(t._1), Some(numBufs),1+Utils.log2Up(numBufs)))
    c.io.input.enable := swap
    c.io.input.countUp := false.B
    (t._1 -> c)
  }
  val statesInR = (0 until numBufs).map{  i => 
    val c = Module(new NBufCtr(1,Some(i), Some(numBufs), 1+Utils.log2Up(numBufs)))
    c.io.input.enable := swap
    c.io.input.countUp := true.B
    c
  }

  val statesOut = (0 until numBufs).map{  i => 
    val c = Module(new NBufCtr(1,Some(i), Some(numBufs), 1+Utils.log2Up(numBufs)))
    c.io.input.enable := swap
    c.io.input.countUp := false.B
    c
  }

  srams.zipWithIndex.foreach{ case (f,i) => 
    wHashmap.foreach { t =>
      val pars = t._2.map{_._1}.reduce{_+_}
      val base = if (t._1 == 0) 0 else (0 until t._1).map{ii => wHashmap.getOrElse(ii, List((0,0))).map{_._1}.reduce{_+_}}.reduce{_+_}
      val wMask = Utils.getRetimed(statesInW(t._1).io.output.count === i.U, {if (Utils.retime) 1 else 0})
      (0 until pars).foreach{ k =>
        val masked_w = Wire(new W_Info(32, List.fill(banks.length)(32), bitWidth))
        masked_w.en := io.w(base+k).en & wMask
        masked_w.data := io.w(base+k).data
        masked_w.ofs := io.w(base+k).ofs
        (0 until banks.length).foreach{ j => masked_w.banks(j) := io.w(base+k).banks(j)}
        f.io.w(base+k) := masked_w
      }
    }
    // (0 until wPar.reduce{_+_}).foreach { k =>
    //   val masked_w = Wire(new multidimW(N, bitWidth))
    //   masked_w.en := io.w(k).en & wMask
    //   masked_w.data := io.w(k).data
    //   masked_w.ofs := io.w(k).ofs
    //   f.io.w(k) := masked_w
    // }

    var idx = 0 
    var idx_meaningful = 0 
    val rSel = (0 until numBufs).map{ a => Utils.getRetimed(statesInR(i).io.output.count === a.U, {if (Utils.retime) 1 else 0}) }
    (0 until maxR).foreach {lane => // Technically only need per read and not per buf but oh well
      // Assemble buffet of read ports
      val buffet = (0 until numBufs).map {p => 
        val size = rHashmap.getOrElse(p, List((0,0))).map{_._1}.reduce{_+_}
        val base = if (p > 0) {(0 until p).map{ q =>
          rHashmap.getOrElse(q,List((0,0))).map{_._1}.reduce{_+_}
          }.reduce{_+_}
          } else {0}
        val dummy_r = Wire(new R_Info(32, List.fill(banks.length)(32)))
        dummy_r.en := false.B
        if (lane < size) {io.r(base + lane)} else dummy_r
      }
      f.io.r(lane) := chisel3.util.Mux1H(rSel, buffet)
    }
    f.io.flow(0) := io.flow.reduce{_&_}
  }

  (0 until numBufs).foreach {i =>
    val sel = (0 until numBufs).map{ a => Utils.getRetimed(statesOut(i).io.output.count === a.U, {if (Utils.retime) 1 else 0}) }
    (0 until maxR).foreach{ j => 
      io.output.data(i*-*maxR + j) := chisel3.util.Mux1H(sel, srams.map{f => f.io.output.data(j)})
    }
  }

  var wInUse = wHashmap.map{(_._1 -> 0)} // Tracket connect write lanes per port
  var bId = 0
  def connectWPort(wBundle: Vec[W_Info], ports: List[Int]) {
    if (ports.length == 1) {
      // Figure out which wPar section this wBundle fits in by finding first false index with same wPar
      val port = ports(0) 
      val wId = wInUse(port)
      val base = if (port == 0) wId else {(0 until port).map{i => wHashmap.getOrElse(i, List((0,0))).map{_._1}.reduce{_+_}}.reduce{_+_} + wId}
      // Get start index of this section
      (0 until wBundle.length).foreach{ i => 
        io.w(base + i) := wBundle(i) 
      }
      // Set this section in use
      wInUse += (port -> {wId + wBundle.length})
    }
  }

  var rInUse = rHashmap.map{(_._1 -> 0)} // Tracking connect read lanes per port
  var flowId = 0
  def connectRPort(rBundle: Vec[R_Info], port: Int): Int = {
    // Figure out which rPar section this wBundle fits in by finding first false index with same rPar
    val rId = rInUse(port)
    // Get start index of this section
    val base = port *-* maxR + rId
    val packbase = if (port > 0) {
      (0 until port).map{p => 
        rHashmap.getOrElse(p, List((0,0))).map{_._1}.reduce{_+_}
      }.reduce{_+_}
    } else {0}
    io.flow(flowId) := true.B
    flowId = flowId + 1
    // Connect to rPar(rId) elements from base
    (0 until rBundle.length).foreach{ i => 
      io.r(packbase + rId + i) := rBundle(i) 
    }
    rInUse += (port -> {rId + rBundle.length})
    base
  }
  def connectRPort(rBundle: Vec[R_Info], port: Int, flow: Bool): Int = {
    // Figure out which rPar section this wBundle fits in by finding first false index with same rPar
    val rId = rInUse(port)
    // Get start index of this section
    val base = port *-* maxR + rId
    val packbase = if (port > 0) {
      (0 until port).map{p => 
        rHashmap.getOrElse(p, List((0,0))).map{_._1}.reduce{_+_}
      }.reduce{_+_}
    } else {0}
    // Connect to rPar(rId) elements from base
    (0 until rBundle.length).foreach{ i => 
      io.r(packbase + rId + i) := rBundle(i) 
    }
    io.flow(flowId) := flow
    flowId = flowId + 1
    rInUse += (port -> {rId + rBundle.length})
    base
  }


  def connectStageCtrl(done: Bool, en: Bool, ports: List[Int]) {
    ports.foreach{ port => 
      io.sEn(port) := en
      io.sDone(port) := done
    }
  }

  def connectUnwrittenPorts(ports: List[Int]) { // TODO: Remnant from maxj?
    // ports.foreach{ port => 
    //   io.input(port).enable := false.B
    // }
  }
 
  // def readTieDown(port: Int) { 
  //   (0 until numReaders).foreach {i => 
  //     io.rSel(port *-* numReaders + i) := false.B
  //   }
  // }

  def connectUntouchedPorts(ports: List[Int]) {
    ports.foreach{ port => 
      io.sEn(port) := false.B
      io.sDone(port) := false.B
    }
  }

  def connectDummyBroadcast() {
  }



}
