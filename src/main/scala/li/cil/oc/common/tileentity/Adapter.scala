package li.cil.oc.common.tileentity

import li.cil.oc.Settings
import li.cil.oc.api
import li.cil.oc.api.Driver
import li.cil.oc.api.internal
import li.cil.oc.api.network.Analyzable
import li.cil.oc.api.network._
import li.cil.oc.common.Slot
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.nbt.NBTTagList
import net.minecraft.util.EnumFacing
import net.minecraftforge.common.util.Constants.NBT

import scala.collection.mutable

class Adapter extends traits.Environment with traits.ComponentInventory with Analyzable with internal.Adapter {
  val node = api.Network.newNode(this, Visibility.Network).create()

  private val blocks = Array.fill[Option[(ManagedEnvironment, api.driver.Block)]](6)(None)

  private val updatingBlocks = mutable.ArrayBuffer.empty[ManagedEnvironment]

  private val blocksData = Array.fill[Option[BlockData]](6)(None)

  // ----------------------------------------------------------------------- //

  override def onAnalyze(player: EntityPlayer, side: EnumFacing, hitX: Float, hitY: Float, hitZ: Float) = blocks collect {
    case Some(((environment, _))) => environment.node
  }

  // ----------------------------------------------------------------------- //

  override def canUpdate = isServer

  override def update() {
    super.update()
    if (updatingBlocks.nonEmpty) {
      for (block <- updatingBlocks) {
        block.update()
      }
    }
  }

  def neighborChanged(d: EnumFacing) {
    if (node != null && node.network != null) {
      val blockPos = getPos.offset(d)
      world.getTileEntity(getPos) match {
        case env: traits.Environment =>
        // Don't provide adaption for our stuffs. This is mostly to avoid
        // cables and other non-functional stuff popping up in the adapter
        // due to having a power interface. Might revisit this at some point,
        // but the only 'downside' is that it can't be used to manipulate
        // inventories, which I actually consider a plus :P
        case _ =>
          Option(api.Driver.driverFor(world, blockPos)) match {
            case Some(newDriver) => blocks(d.ordinal()) match {
              case Some((oldEnvironment, driver)) =>
                if (newDriver != driver) {
                  // This is... odd. Maybe moved by some other mod? First, clean up.
                  blocks(d.ordinal()) = None
                  updatingBlocks -= oldEnvironment
                  blocksData(d.ordinal()) = None
                  node.disconnect(oldEnvironment.node)

                  // Then rebuild - if we have something.
                  val environment = newDriver.createEnvironment(world, blockPos)
                  if (environment != null) {
                    blocks(d.ordinal()) = Some((environment, newDriver))
                    if (environment.canUpdate) {
                      updatingBlocks += environment
                    }
                    blocksData(d.ordinal()) = Some(new BlockData(environment.getClass.getName, new NBTTagCompound()))
                    node.connect(environment.node)
                  }
                } // else: the more things change, the more they stay the same.
              case _ =>
                // A challenger appears. Maybe.
                val environment = newDriver.createEnvironment(world, blockPos)
                if (environment != null) {
                  blocks(d.ordinal()) = Some((environment, newDriver))
                  if (environment.canUpdate) {
                    updatingBlocks += environment
                  }
                  blocksData(d.ordinal()) match {
                    case Some(data) if data.name == environment.getClass.getName =>
                      environment.load(data.data)
                    case _ =>
                  }
                  blocksData(d.ordinal()) = Some(new BlockData(environment.getClass.getName, new NBTTagCompound()))
                  node.connect(environment.node)
                }
            }
            case _ => blocks(d.ordinal()) match {
              case Some((environment, driver)) =>
                // We had something there, but it's gone now...
                node.disconnect(environment.node)
                environment.save(blocksData(d.ordinal()).get.data)
                Option(environment.node).foreach(_.remove())
                blocks(d.ordinal()) = None
                updatingBlocks -= environment
              case _ => // Nothing before, nothing now.
            }
          }
      }
    }
  }

  def neighborChanged() {
    if (node != null && node.network != null) {
      for (d <- EnumFacing.values) {
        neighborChanged(d)
      }
    }
  }

  // ----------------------------------------------------------------------- //

  override def onConnect(node: Node) {
    super.onConnect(node)
    if (node == this.node) {
      neighborChanged()
    }
  }

  override def onDisconnect(node: Node) {
    super.onDisconnect(node)
    if (node == this.node) {
      updatingBlocks.clear()
    }
  }

  // ----------------------------------------------------------------------- //

  override def getSizeInventory = 1

  override def isItemValidForSlot(slot: Int, stack: ItemStack) = (slot, Option(Driver.driverFor(stack, getClass))) match {
    case (0, Some(driver)) => driver.slot(stack) == Slot.Upgrade
    case _ => false
  }

  // ----------------------------------------------------------------------- //

  override def readFromNBT(nbt: NBTTagCompound) {
    super.readFromNBT(nbt)

    val blocksNbt = nbt.getTagList(Settings.namespace + "adapter.blocks", NBT.TAG_COMPOUND)
    (0 until (blocksNbt.tagCount min blocksData.length)).
      map(blocksNbt.getCompoundTagAt).
      zipWithIndex.
      foreach {
      case (blockNbt, i) =>
        if (blockNbt.hasKey("name") && blockNbt.hasKey("data")) {
          blocksData(i) = Some(new BlockData(blockNbt.getString("name"), blockNbt.getCompoundTag("data")))
        }
    }
  }

  override def writeToNBT(nbt: NBTTagCompound) {
    super.writeToNBT(nbt)

    val blocksNbt = new NBTTagList()
    for (i <- 0 until blocks.length) {
      val blockNbt = new NBTTagCompound()
      blocksData(i) match {
        case Some(data) =>
          blocks(i) match {
            case Some((environment, _)) => environment.save(data.data)
            case _ =>
          }
          blockNbt.setString("name", data.name)
          blockNbt.setTag("data", data.data)
        case _ =>
      }
      blocksNbt.appendTag(blockNbt)
    }
    nbt.setTag(Settings.namespace + "adapter.blocks", blocksNbt)
  }

  // ----------------------------------------------------------------------- //

  private class BlockData(val name: String, val data: NBTTagCompound)

}
