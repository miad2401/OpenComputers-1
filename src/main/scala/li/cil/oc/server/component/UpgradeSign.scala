package li.cil.oc.server.component

import li.cil.oc.Settings
import li.cil.oc.api.driver.EnvironmentHost
import li.cil.oc.api.internal.Robot
import li.cil.oc.api.prefab
import li.cil.oc.util.BlockPosition
import li.cil.oc.util.ExtendedWorld._
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.tileentity.TileEntitySign
import net.minecraft.util.ChatComponentText
import net.minecraft.util.EnumFacing
import net.minecraft.world.WorldServer
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.event.world.BlockEvent
import net.minecraftforge.fml.common.eventhandler.Event

abstract class UpgradeSign extends prefab.ManagedEnvironment {
  def host: EnvironmentHost

  protected def getValue(tileEntity: Option[TileEntitySign]): Array[AnyRef] = {
    tileEntity match {
      case Some(sign) => result(sign.signText.mkString("\n"))
      case _ => result(Unit, "no sign")
    }
  }

  protected def setValue(tileEntity: Option[TileEntitySign], text: String): Array[AnyRef] = {
    tileEntity match {
      case Some(sign) =>
        val player = host match {
          case robot: Robot => robot.player
          case _ => FakePlayerFactory.get(host.world.asInstanceOf[WorldServer], Settings.get.fakePlayerProfile)
        }
        if (!canChangeSign(player, sign)) {
          return result(Unit, "not allowed")
        }

        text.lines.padTo(4, "").map(line => if (line.length > 15) line.substring(0, 15) else line).map(new ChatComponentText(_)).copyToArray(sign.signText)
        host.world.markBlockForUpdate(sign.getPos)
        result(sign.signText.mkString("\n"))
      case _ => result(Unit, "no sign")
    }
  }

  protected def findSign(side: EnumFacing) = {
    val hostPos = BlockPosition(host)
    host.world.getTileEntity(hostPos) match {
      case sign: TileEntitySign => Option(sign)
      case _ => host.world.getTileEntity(hostPos.offset(side)) match {
        case sign: TileEntitySign => Option(sign)
        case _ => None
      }
    }
  }

  private def canChangeSign(player: EntityPlayer, tileEntity: TileEntitySign): Boolean = {
    if (!host.world.isBlockModifiable(player, tileEntity.getPos)) {
      return false
    }
    val event = new BlockEvent.BreakEvent(host.world, tileEntity.getPos, tileEntity.getWorld.getBlockState(tileEntity.getPos), player)
    MinecraftForge.EVENT_BUS.post(event)
    !(event.isCanceled || event.getResult == Event.Result.DENY)
  }
}
