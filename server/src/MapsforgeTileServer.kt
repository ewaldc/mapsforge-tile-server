package org.develar.mapsforgeTileServer

import com.google.common.collect.ImmutableMap
import com.google.common.collect.Iterators
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollServerSocketChannel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpRequestDecoder
import io.netty.handler.codec.http.HttpResponseEncoder
import io.netty.util.concurrent.Future
import org.develar.mapsforgeTileServer.pixi.processPaths
import org.kohsuke.args4j.CmdLineException
import org.kohsuke.args4j.CmdLineParser
import org.mapsforge.core.graphics.GraphicFactory
import org.mapsforge.core.graphics.TileBitmap
import org.mapsforge.map.awt.AwtGraphicFactory
import org.mapsforge.map.awt.AwtTileBitmap
import org.mapsforge.map.model.DisplayModel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.File
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.ArrayList
import java.util.Enumeration
import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import javax.imageio.ImageIO

val LOG:Logger = LoggerFactory.getLogger(javaClass<MapsforgeTileServer>())

class MyAwtGraphicFactory() : AwtGraphicFactory() {
  override fun createTileBitmap(tileSize: Int, hasAlpha: Boolean): TileBitmap {
    return AwtTileBitmap(BufferedImage(tileSize, tileSize, if (hasAlpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_3BYTE_BGR))
  }
}

val AWT_GRAPHIC_FACTORY: GraphicFactory = MyAwtGraphicFactory()

public fun main(args: Array<String>) {
  ImageIO.setUseCache(false)

  val options = Options()
  //printUsage(options);
  try {
    CmdLineParser(options).parseArgument(args.toList())
  }
  catch (e: CmdLineException) {
    System.err.print(e.getMessage())
    System.exit(64)
  }

  val maps = ArrayList<File>(options.maps!!.size())
  processPaths(options.maps!!, ".map", Integer.MAX_VALUE, object: Consumer<Path> {
    override fun accept(path: Path) {
      maps.add(path.toFile())
    }
  })

  if (maps.isEmpty()) {
    LOG.error("No map specified")
    return
  }

  if (options.maxFileCacheSize == -2.0) {
    options.maxFileCacheSize = System.getenv("MAX_FILE_CACHE_SIZE")?.toDouble() ?: 30.0
  }

  val mapsforgeTileServer: MapsforgeTileServer
  try {
    mapsforgeTileServer = MapsforgeTileServer(maps, options.themes!!)
  }
  catch (e: IllegalStateException) {
    LOG.error(e.getMessage())
    return
  }

  mapsforgeTileServer.startServer(options)
}

SuppressWarnings("UnusedDeclaration")
private fun printUsage(options: Options) {
  CmdLineParser(options).printUsage(OutputStreamWriter(System.out), object : ResourceBundle() {
    private val data = ImmutableMap.Builder<String, String>().put("FILE", "<path>").put("PATH", "<path>").put("VAL", "<string>").put("N", " <int>").build()

    override fun handleGetObject(key: String): Any {
      return data.get(key) ?: key
    }

    override fun getKeys(): Enumeration<String> {
      return Iterators.asEnumeration(data.keySet().iterator())
    }
  })
}

private fun getAvailableMemory(): Long {
  val runtime = Runtime.getRuntime()
  val totalMemory = runtime.totalMemory() // current heap allocated to the VM process
  val freeMemory = runtime.freeMemory() // out of the current heap, how much is free
  val maxMemory = runtime.maxMemory() // max heap VM can use e.g. Xmx setting
  val usedMemory = totalMemory - freeMemory // how much of the current heap the VM is using
  // available memory i.e. Maximum heap size minus the current amount used
  return maxMemory - usedMemory
}

class MapsforgeTileServer(val maps: List<File>, renderThemeFiles: Array<Path>) {
  val displayModel = DisplayModel()
  val renderThemeManager = RenderThemeManager(renderThemeFiles, displayModel)

  fun startServer(options: Options) {
    val isLinux = System.getProperty("os.name")!!.toLowerCase(Locale.ENGLISH).startsWith("linux")
    val eventGroup = if (isLinux) EpollEventLoopGroup() else NioEventLoopGroup()
    val channelRegistrar = ChannelRegistrar()

    val eventGroupShutdownFeature = AtomicReference<Future<*>>()
    val shutdownHooks = ArrayList<()->Unit>(4)
    shutdownHooks.add {
      LOG.info("Shutdown server");
      try {
        channelRegistrar.closeAndSyncUninterruptibly();
      }
      finally {
        if (!eventGroupShutdownFeature.compareAndSet(null, eventGroup.shutdownGracefully())) {
          LOG.error("ereventGroupShutdownFeature was already set");
        }

        renderThemeManager.dispose()
      }
    }

    val executorCount = eventGroup.executorCount()
    val fileCacheManager = if (options.maxFileCacheSize == 0.0) null else FileCacheManager(options, executorCount, shutdownHooks)

    val maxMemoryCacheSize = getAvailableMemory() - (64 * 1024 * 1024).toLong() /* leave 64MB for another stuff */
    if (maxMemoryCacheSize <= 0) {
      val runtime = Runtime.getRuntime()
      LOG.error("Memory not enough, current free memory " + runtime.freeMemory() + ", total memory " + runtime.totalMemory() + ", max memory " + runtime.maxMemory())
      return
    }
    val tileHttpRequestHandler = TileHttpRequestHandler(this, fileCacheManager, executorCount, maxMemoryCacheSize, shutdownHooks)

    // task "sync eventGroupShutdownFeature only" must be last
    shutdownHooks.add {
      eventGroupShutdownFeature.getAndSet(null)!!.syncUninterruptibly()
    }

    val serverBootstrap = ServerBootstrap()
    serverBootstrap.group(eventGroup).channel(if (isLinux) javaClass<EpollServerSocketChannel>() else javaClass<NioServerSocketChannel>()).childHandler(object : ChannelInitializer<Channel>() {
      override fun initChannel(channel: Channel) {
        channel.pipeline().addLast(channelRegistrar)
        channel.pipeline().addLast(HttpRequestDecoder(), HttpObjectAggregator(1048576 * 10), HttpResponseEncoder())
        channel.pipeline().addLast(tileHttpRequestHandler)
      }
    }).childOption<Boolean>(ChannelOption.SO_KEEPALIVE, true).childOption<Boolean>(ChannelOption.TCP_NODELAY, true)

    val address = if (options.host.orEmpty().isEmpty()) InetSocketAddress(InetAddress.getLoopbackAddress(), options.port) else InetSocketAddress(options.host!!, options.port)
    val serverChannel = serverBootstrap.bind(address).syncUninterruptibly().channel()
    channelRegistrar.addServerChannel(serverChannel)

    Runtime.getRuntime().addShutdownHook(Thread(object: Runnable {
      override fun run() {
        for (shutdownHook in shutdownHooks) {
          try {
            shutdownHook()
          }
          catch (e: Throwable) {
            LOG.error(e.getMessage(), e);
          }
        }
      }
    }))

    LOG.info("Listening " + address.getHostName() + ":" + address.getPort())
    serverChannel.closeFuture().syncUninterruptibly()
  }
}