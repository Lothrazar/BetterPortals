package de.johni0702.minecraft.betterportals.server.view

import com.mojang.authlib.GameProfile
import de.johni0702.minecraft.betterportals.LOGGER
import de.johni0702.minecraft.betterportals.common.provideDelegate
import de.johni0702.minecraft.betterportals.net.CreateView
import de.johni0702.minecraft.betterportals.net.DestroyView
import de.johni0702.minecraft.betterportals.net.ViewData
import de.johni0702.minecraft.betterportals.net.sendTo
import de.johni0702.minecraft.betterportals.server.NettyExceptionHandler
import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.network.EnumPacketDirection
import net.minecraft.network.NetHandlerPlayServer
import net.minecraft.network.NettyPacketEncoder
import net.minecraft.network.NettyVarint21FrameEncoder
import net.minecraft.network.play.server.SPacketDestroyEntities
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.Vec3d
import net.minecraft.world.WorldServer
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.world.WorldEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.PlayerEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher
import java.util.*

internal class ServerViewManagerImpl(
        val server: MinecraftServer,
        val connection: NetHandlerPlayServer
) : ServerViewManager {

    override val player: EntityPlayerMP
        get() = connection.player

    override var mainView = ServerViewImpl(this, 0, player, null)

    override val views = mutableListOf(mainView)

    private val eventHandler = EventHandler()
    private var nextViewId = 1

    init {
        eventHandler.registered = true
    }

    override fun createView(world: WorldServer, pos: Vec3d, beforeSendChunks: EntityPlayerMP.() -> Unit): ServerView {
        val id = nextViewId++
        val gameProfile = GameProfile(UUID.randomUUID(), connection.player.name + "[view]")
        val camera = ViewEntity(world, gameProfile, connection)
        camera.setPosition(pos.x, pos.y, pos.z) // No update (networking not yet set up)

        val channel = EmbeddedChannel()
        channel.pipeline()
                .addLast("prepender", NettyVarint21FrameEncoder())
                .addLast("encoder", NettyPacketEncoder(EnumPacketDirection.CLIENTBOUND))
                .addLast("exception_handler", NettyExceptionHandler(connection))
                .addLast("packet_handler", camera.connection.networkManager)
                .fireChannelActive()

        val networkDispatcher = NetworkDispatcher.allocAndSet(camera.connection.networkManager, server.playerList)
        channel.pipeline().addBefore("packet_handler", "fml:packet_handler", networkDispatcher)

        val view = ServerViewImpl(this, id, camera, channel)
        views.add(view)

        CreateView(id, camera.dimension, world.difficulty,
                world.worldInfo.gameType, world.worldType).sendTo(connection.player)
        world.spawnEntity(camera)
        beforeSendChunks(camera)
        server.playerList.preparePlayer(camera, null)
        server.playerList.updateTimeAndWeatherForPlayer(camera, world)
        camera.connection.setPlayerLocation(camera.posX, camera.posY, camera.posZ, camera.rotationYaw, camera.rotationPitch)

        // Ensure the view entity position and world is synced to the client
        flushPackets()
        return view
    }

    internal fun destroyView(view: ServerViewImpl) {
        if (!connection.netManager.isChannelOpen) return
        if (!views.remove(view)) {
            throw RuntimeException("unknown view $view")
        }
        DestroyView(view.id).sendTo(connection.player)

        val camera = view.camera
        val world = camera.serverWorld
        world.removeEntity(camera)
        world.playerChunkMap.removePlayer(camera)
    }

    private fun destroy() {
        eventHandler.registered = false

        views.forEach { view ->
            if (view.isMainView) return@forEach
            val camera = view.camera
            val world = camera.serverWorld
            world.removeEntity(camera)
            world.playerChunkMap.removePlayer(camera)
        }
        views.clear()
    }

    private fun tick() {
        views.filter { it.refCnt == 0 }.forEach {
            if (it.isMainView) {
                LOGGER.warn("Main view of $player somehow reached a refCnt of 0!")
                it.retain()
                return@forEach
            }
            destroyView(it)
        }

        flushPackets()
    }

    override fun flushPackets() {
        // For some reason MC queues up removed entity ids instead of sending them directly (maybe to save packets?).
        // Anyhow, we need them sent out right now.
        val flushEntityPackets = { player: EntityPlayerMP ->
            if (player.entityRemoveQueue.isNotEmpty()) {
                player.connection.sendPacket(SPacketDestroyEntities(*(player.entityRemoveQueue.toIntArray())))
                player.entityRemoveQueue.clear()
            }
        }
        flushEntityPackets(connection.player)
        views.forEach { flushEntityPackets(it.camera) }

        // Flush view packets via main connection
        views.forEach { view ->
            view.channel?.outboundMessages()?.onEach {
                ViewData(view.id, it as ByteBuf).sendTo(connection.player)
            }?.clear()
        }
    }

    private inner class EventHandler {
        var registered by MinecraftForge.EVENT_BUS

        @SubscribeEvent
        fun onPlayerLeft(event: PlayerEvent.PlayerLoggedOutEvent) {
            if ((event.player as? EntityPlayerMP)?.connection === connection) {
                destroy()
            }
        }

        @SubscribeEvent
        fun postTick(event: TickEvent.ServerTickEvent) {
            if (event.phase != TickEvent.Phase.END) return

            tick()
        }

        @SubscribeEvent
        fun onWorldUnload(event: WorldEvent.Unload) {
            views.filter { !it.isMainView && it.camera.world === event.world }.forEach {
                if (it.refCnt > 0) {
                    LOGGER.warn("View $it has a refCnt of ${it.refCnt} even though its world is unloaded!")
                }
                destroyView(it)
            }
        }
    }
}