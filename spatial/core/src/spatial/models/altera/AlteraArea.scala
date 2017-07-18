package spatial.models
package altera

case class AlteraArea(
                      lut3: Int = 0,
                      lut4: Int = 0,
                      lut5: Int = 0,
                      lut6: Int = 0,
                      lut7: Int = 0,
                      mem16: Int = 0,
                      mem32: Int = 0,
                      mem64: Int = 0,
                      regs: Int = 0,
                      mregs: Int = 0,   // Memory registers (used in place of SRAMs)
                      dsps: Int = 0,
                      sram: Int = 0,
                      channels: Int = 0
                     )
{
  def +(that: AlteraArea) = AlteraArea(
    lut3 = this.lut3 + that.lut3,
    lut4 = this.lut4 + that.lut4,
    lut5 = this.lut5 + that.lut5,
    lut6 = this.lut6 + that.lut6,
    lut7 = this.lut7 + that.lut7,
    mem16 = this.mem16 + that.mem16,
    mem32 = this.mem32 + that.mem32,
    mem64 = this.mem64 + that.mem64,
    regs = this.regs + that.regs,
    mregs = this.mregs + that.mregs,
    dsps = this.dsps + that.dsps,
    sram = this.sram + that.sram,
    channels = this.channels + that.channels
  )

  def replicate(x: Int, inner: Boolean) = AlteraArea(
    lut3 = this.lut3 * x,
    lut4 = this.lut4 * x,
    lut5 = this.lut5 * x,
    lut6 = this.lut6 * x,
    lut7 = this.lut7 * x,
    mem16 = this.mem16 * x,
    mem32 = this.mem32 * x,
    mem64 = this.mem64 * x,
    regs  = this.regs * x,
    mregs = if (inner) mregs else mregs * x,
    dsps = this.dsps * x,
    sram = if (inner) sram else sram * x,
    channels = channels * x
  )
  def isNonzero: Boolean = lut7 > 0 || lut6 > 0 || lut5 > 0 || lut4 > 0 || lut3 > 0 ||
    mem64 > 0 || mem32 > 0 || mem16 > 0 || regs > 0 || dsps > 0 || sram > 0 || mregs > 0 || channels > 0

  override def toString: String = s"lut3=$lut3, lut4=$lut4, lut5=$lut5, lut6=$lut6, lut7=$lut7, mem16=$mem16, mem32=$mem32, mem64=$mem64, regs=$regs, dsps=$dsps, bram=$sram, mregs=$mregs"

  def toArray: Array[Int] = Array(lut3,lut4,lut5,lut6,lut7,mem16,mem32,mem64,regs+mregs,dsps,sram)

  def <(that: AlteraArea): Boolean = {
    this.lut3 < that.lut3 &&
    this.lut4 < that.lut4 &&
    this.lut5 < that.lut5 &&
    this.lut6 < that.lut6 &&
    this.lut7 < that.lut7 &&
    this.mem16 < that.mem16 &&
    this.mem32 < that.mem32 &&
    this.mem64 < that.mem64 &&
    this.regs < that.regs &&
    this.mregs < that.mregs &&
    this.dsps < that.dsps &&
    this.sram < that.sram &&
    this.channels < that.channels
  }
}

case class AlteraAreaSummary(
  alms: Double,
  regs: Double,
  dsps: Double,
  bram: Double,
  channels: Double
) extends AreaSummary[AlteraAreaSummary] {
  override def headings: List[String] = List("ALMs", "Regs", "DSPs", "BRAMs")
  override def toFile: List[Double] = List(alms, dsps, bram)
  override def toList: List[Double] = List(alms, regs, dsps, bram, channels)
  override def fromList(items: List[Double]): AlteraAreaSummary = AlteraAreaSummary(items(0),items(1),items(2),items(3),items(4))
}

object AlteraAreaMetric extends AreaMetric[AlteraArea] {
  override def plus(a: AlteraArea, b: AlteraArea): AlteraArea = a + b
  override def zero: AlteraArea = AlteraArea()
  override def lessThan(x: AlteraArea, y: AlteraArea): Boolean = x < y
  override def times(a: AlteraArea, x: Int, isInner: Boolean): AlteraArea = a.replicate(x, isInner)

  override def sramArea(n: Int): AlteraArea = AlteraArea(sram=n)
  override def regArea(n: Int, bits: Int): AlteraArea = AlteraArea(regs = n * bits)
  override def muxArea(n: Int, bits: Int): AlteraArea = AlteraArea(lut3=n*bits, regs=n*bits)
}

object AlteraArea {
  implicit def alteraAreaIsAreaMetric: AreaMetric[AlteraArea] = AlteraAreaMetric
}
