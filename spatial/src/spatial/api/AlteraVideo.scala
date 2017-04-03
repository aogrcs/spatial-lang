package spatial.api

import argon.core.Staging
import spatial.SpatialExp

// TODO: Is this still used by anything? If not, delete
trait AlteraVideoApi extends AlteraVideoExp with ControllerApi with FIFOApi with RangeApi with PinApi{
  this: SpatialExp =>

  def AXI_Master_Slave()(implicit ctx: SrcCtx): AXI_Master_Slave = AXI_Master_Slave(axi_ms_alloc())

  def Decoder_Template[T:Type:Bits](popFrom: StreamIn[T], pushTo: FIFO[T])(implicit ctx: SrcCtx): Decoder_Template[T] = {
    Decoder_Template(decoder_alloc[T](popFrom.s.asInstanceOf[Exp[T]], pushTo.s.asInstanceOf[Exp[T]]))
  }

  def DMA_Template[T:Type:Bits](popFrom: FIFO[T], loadIn: SRAM1[T])(implicit ctx: SrcCtx): DMA_Template[T] = {
    DMA_Template(dma_alloc[T](popFrom.s.asInstanceOf[Exp[T]], loadIn.s.asInstanceOf[Exp[T]]))
  }

  def Decoder[T:Type:Bits,C[T]](popFrom: StreamIn[T], pushTo: FIFO[T])(implicit ctx: SrcCtx): Void = {
    Pipe { 
      Decoder_Template(popFrom, pushTo)
      popFrom.value()
      ()
      // pushTo.enq(popFrom.deq())
    }
  }

  def DMA[T:Type:Bits](popFrom: FIFO[T], loadIn: SRAM1[T] /*frameRdy:  StreamOut[T]*/)(implicit ctx: SrcCtx): Void = {
    Pipe {
      DMA_Template(popFrom, loadIn)
      Foreach(64 by 1){ i =>
        loadIn(i) = popFrom.deq()
      }
      // Pipe {
      //   frameRdy.push(1.to[T])
      // }
      ()
    }
  }

}


trait AlteraVideoExp extends Staging with MemoryExp {
  this: SpatialExp =>

  /** Infix methods **/
  // TODO: Do these need to be staged types?
  case class AXI_Master_Slave(s: Exp[AXI_Master_Slave]) extends Template[AXI_Master_Slave]
  case class Decoder_Template[T:Meta:Bits](s: Exp[Decoder_Template[T]]) extends Template[Decoder_Template[T]]
  case class DMA_Template[T:Meta:Bits](s: Exp[DMA_Template[T]]) extends Template[DMA_Template[T]]

  /** Staged Type **/
  object AXIMasterSlaveType extends Meta[AXI_Master_Slave] {
    override def wrapped(x: Exp[AXI_Master_Slave]) = AXI_Master_Slave(x)
    override def stagedClass = classOf[AXI_Master_Slave]
    override def isPrimitive = false // ???
  }
  implicit def aXIMasterSlaveType: Meta[AXI_Master_Slave] = AXIMasterSlaveType

  case class DecoderTemplateType[T:Bits](child: Meta[T]) extends Meta[Decoder_Template[T]] {
    override def wrapped(x: Exp[Decoder_Template[T]]) = Decoder_Template(x)(child,bits[T])
    override def typeArguments = List(child)
    override def stagedClass = classOf[Decoder_Template[T]]
    override def isPrimitive = true // ???
  }
  implicit def decoderTemplateType[T:Meta:Bits]: Meta[Decoder_Template[T]] = DecoderTemplateType(meta[T])

  case class DMATemplateType[T:Bits](child: Meta[T]) extends Meta[DMA_Template[T]] {
    override def wrapped(x: Exp[DMA_Template[T]]) = DMA_Template[T](x)(child,bits[T])
    override def typeArguments = List(child)
    override def stagedClass = classOf[DMA_Template[T]]
    override def isPrimitive = true // ???
  }
  implicit def dMATemplateType[T:Meta:Bits]: Meta[DMA_Template[T]] = DMATemplateType(meta[T])


  /** IR Nodes **/
  case class AxiMSNew() extends Op[AXI_Master_Slave] {
    def mirror(f:Tx) = axi_ms_alloc()
  }
  case class DecoderTemplateNew[T:Type:Bits](popFrom: Exp[T], pushTo: Exp[T]) extends Op[Decoder_Template[T]] {
    def mirror(f:Tx) = decoder_alloc[T](f(popFrom), f(pushTo))
    val mT = typ[T]
    val bT = bits[T]

  }
  case class DMATemplateNew[T:Type:Bits](popFrom: Exp[T], loadIn: Exp[T]) extends Op[DMA_Template[T]] {
    def mirror(f:Tx) = dma_alloc[T](f(popFrom), f(loadIn))
    val mT = typ[T]
    val bT = bits[T]
  }

  /** Constructors **/
  def axi_ms_alloc()(implicit ctx: SrcCtx): Sym[AXI_Master_Slave] = {
    stageSimple( AxiMSNew() )(ctx)
  }
  def decoder_alloc[T:Type:Bits](popFrom: Exp[T], pushTo: Exp[T])(implicit ctx: SrcCtx): Sym[Decoder_Template[T]] = {
    stageSimple( DecoderTemplateNew[T](popFrom, pushTo) )(ctx)
  }
  def dma_alloc[T:Type:Bits](popFrom: Exp[T], loadIn: Exp[T])(implicit ctx: SrcCtx): Sym[DMA_Template[T]] = {
    stageSimple( DMATemplateNew[T](popFrom, loadIn) )(ctx)
  }

  /** Internal methods **/

  // private[spatial] def source(x: Exp[Reg]): Exp = x match {
  //   case Op(AxiMSNew())    => 
  // }

}

