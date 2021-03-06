package de.johni0702.minecraft.betterportals.server

import de.johni0702.minecraft.betterportals.LOGGER
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import net.minecraft.network.NetHandlerPlayServer

class NettyExceptionHandler(
        private val parentConnection: NetHandlerPlayServer
) : ChannelInboundHandlerAdapter() {
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        LOGGER.error("Exception caught in net handler of view of ${parentConnection.player}: ", cause)

        @Suppress("DEPRECATION")
        parentConnection.netManager.exceptionCaught(ctx, cause)
    }
}