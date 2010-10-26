package com.twitter.finagle.streaming

import java.util.concurrent.Executors
import java.net.InetSocketAddress
import java.nio.charset.Charset

import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.channel.{
  Channels, ChannelPipelineFactory, SimpleChannelUpstreamHandler,
  ChannelHandlerContext, MessageEvent}
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.handler.codec.http.{
  HttpClientCodec, DefaultHttpRequest, HttpVersion, HttpMethod,
  HttpHeaders, HttpChunk}

import com.twitter.finagle.http
import com.twitter.finagle.util.{
  Ok, Error, Cancelled,
  ChannelBufferSnooper, SimpleChannelSnooper}
import com.twitter.finagle.util.Conversions._
import com.twitter.finagle.channel._

object Streaming {
  val STREAM_HOST = "stream.twitter.com"

  def streamingClientBroker = {
    val socketChannelFactory =
      new NioClientSocketChannelFactory(
        Executors.newCachedThreadPool(), Executors.newCachedThreadPool())

    val bootstrap = new ClientBootstrap
    bootstrap.setFactory(socketChannelFactory)
    bootstrap.setOption("remoteAddress", new InetSocketAddress(STREAM_HOST, 80))
    bootstrap.setPipelineFactory(new ChannelPipelineFactory {
      def getPipeline = {
        val pipeline = Channels.pipeline()
        // pipeline.addLast("snooper", new SimpleChannelSnooper("chan"))
        // pipeline.addLast("snooper_", new ChannelBufferSnooper("http"))
        pipeline.addLast("http", new HttpClientCodec)
        pipeline.addLast("lifecycleSpy", http.RequestLifecycleSpy)
        pipeline
      }
    })

    new BootstrapBroker(bootstrap)
  }

  def main(args: Array[String]) {
    val bootstrap = new ClientBootstrap(new BrokeredChannelFactory)
    bootstrap.setPipelineFactory(new ChannelPipelineFactory {
      def getPipeline = {
        val pipeline = Channels.pipeline()
        // pipeline.addLast("snooper", new SimpleChannelSnooper("app"))
        pipeline.addLast("counter", new SimpleChannelUpstreamHandler {
          var count = 0
          override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
            e.getMessage match {
              case chunk: HttpChunk =>
                count += 1
                if (count % 1000 == 0)
                  println("count: %d".format(count))
                //println(chunk.getContent().toString(Charset.forName("UTF-8")))
              case _ =>
            }
          }
        })

        pipeline
      }
    })

    bootstrap.connect(streamingClientBroker) {
      case Ok(channel) =>
        val request = new DefaultHttpRequest(
          HttpVersion.HTTP_1_1, HttpMethod.GET, "/1/statuses/firehose.json")

        // Special skunkstream user.
        request.setHeader(
          HttpHeaders.Names.AUTHORIZATION,
          "Basic c2t1bmtzdHJlYW06a29sNXplcm42aQ==")
        request.setHeader(HttpHeaders.Names.HOST, STREAM_HOST)

        Channels.write(channel, request)
    }
  }
}