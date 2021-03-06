package fringe

import chisel3._
import chisel3.util._
import templates.Utils.log2Up
import scala.language.reflectiveCalls

class FIFOArbiter[T<:Data] (val t: T, val d: Int, val v: Int, val numStreams: Int) extends Module {
  val tagWidth = log2Up(numStreams)

  val io = IO(new Bundle {
    val fifo = Vec(numStreams, Flipped(new FIFOBaseIO(t, d, v)))
    val enq = Input(Vec(numStreams, Vec(v, t.cloneType)))
    val enqVld = Input(Vec(numStreams, Bool()))
    val full = Output(Vec(numStreams, Bool()))
    val deq = Output(Vec(v, t.cloneType))
    val deqVld = Input(Bool())
    val forceTag = Input(Valid(UInt(tagWidth.W)))
    val empty = Output(Bool())
    val tag = Output(UInt(tagWidth.W))
    val config = Input(new FIFOOpcode(d, v))
    val fifoSize = Output(UInt(32.W))
  })

  val tagFF = Module(new FF(tagWidth))
  tagFF.io.init := 0.U
  val tag = Mux(io.forceTag.valid, io.forceTag.bits, tagFF.io.out)

  // FIFOs
  if (numStreams > 0) {
    io.fifo.zipWithIndex.foreach { case (f, i) =>
      val fifoConfig = Wire(new FIFOOpcode(d, v))
      fifoConfig.chainRead := io.config.chainRead
      fifoConfig.chainWrite := io.config.chainWrite

      f.config := fifoConfig
      f.enq := io.enq(i)
      f.enqVld := io.enqVld(i)
      f.deqVld := io.deqVld & (tag === i.U)
      io.full(i) := f.full
    }

    val enqSomething = io.enqVld.reduce{_|_}
    val allFifoEmpty = io.fifo.map { _.empty }.reduce{_&_}
    tagFF.io.enable := io.deqVld | (allFifoEmpty & enqSomething)

    val fifoValids = Mux(allFifoEmpty,
      io.enqVld,
      Vec(List.tabulate(numStreams) { i =>
        ~((~io.enqVld(i) & io.fifo(i).empty) | ((tag === i.U) & io.deqVld & ~io.enqVld(i) & io.fifo(i).almostEmpty))
      })
    )

    // Priority encoder and output interfaces
    val activeFifo = PriorityEncoder(fifoValids)
    tagFF.io.in := activeFifo

    val outMux = Module(new MuxVec(t, numStreams, v))
    outMux.io.ins := Vec(io.fifo.map {e => e.deq})
    outMux.io.sel := tag

    val sizeMux = Module(new MuxN(UInt(32.W), numStreams))
    sizeMux.io.ins := Vec(io.fifo.map {e => e.fifoSize})
    sizeMux.io.sel := tag

    io.tag := tag
    io.deq := outMux.io.out
    io.fifoSize := sizeMux.io.out
    val empties = Array.tabulate(numStreams) { i => (i.U -> io.fifo(i).empty) }
    io.empty := MuxLookup(tag, false.B, empties)
    // io.empty := fifos.map {e => e.io.empty}.reduce{_&_}  // emptyMux.io.out
  } else { // Arbiter does nothing if there are no memstreams
    io.tag := 0.U(tagWidth.W)
    io.deq := Vec(List.tabulate(v) { i => 0.U(t.getWidth) })
    io.empty := true.B
  }

}

