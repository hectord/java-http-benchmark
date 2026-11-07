package bench.plain;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * APP 05 - Plain Netty. No Reactor, no Spring.
 *
 * <p><b>Why this app exists.</b> App 01 uses reactor-netty, which is Netty plus a
 * reactive layer: every request builds a {@code Mono}, runs it through
 * {@code HttpServerOperations}, and returns a {@code Publisher<Void>} that the
 * pipeline subscribes to. By writing the same endpoint directly against Netty's
 * {@code ChannelPipeline}, the difference between app 01 and app 05 is the
 * Reactor tax and nothing else. Same Netty version (4.1.135.Final), same JDK,
 * same heap, same event-loop count.
 *
 * <p><b>Pipeline.</b> Deliberately the minimum that is still a correct HTTP/1.1
 * server:
 * <pre>
 *   HttpServerCodec        - bytes  &lt;-&gt; HttpRequest/HttpResponse
 *   HttpObjectAggregator   - HttpRequest+body -&gt; FullHttpRequest
 *   RequestHandler         - our code
 * </pre>
 * Reactor Netty installs more than this (its own operations handler, HTTP/2
 * upgrade support, metrics hooks, protocol validators), which is part of what
 * the delta measures.
 *
 * <p><b>Fairness details that matter.</b>
 * <ul>
 *   <li>Never blocks an event loop: {@code /slow} uses
 *       {@code ctx.executor().schedule(...)}, the direct equivalent of
 *       {@code Mono.delay(50ms)}.</li>
 *   <li>{@code SimpleChannelInboundHandler} auto-releases the request, so no
 *       reference-count leak.</li>
 *   <li>Body is a pre-encoded {@code byte[]} wrapped per response
 *       ({@code Unpooled.wrappedBuffer} is zero-copy), matching app 01.</li>
 *   <li>Keep-alive honours {@code HttpUtil.isKeepAlive} so {@code wrk} can reuse
 *       connections like it does against the other apps.</li>
 *   <li>{@code writeAndFlush(..., voidPromise())} avoids allocating a promise per
 *       response - the cheapest correct write.</li>
 * </ul>
 *
 * <p>Run: {@code ./run.sh 8085 2}
 */
public class PlainNetty {

    private static final byte[] JSON =
            "{\"ok\":true,\"id\":12345,\"name\":\"bench\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SLOW_BODY = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8085;
        int loops = args.length > 1 ? Integer.parseInt(args[1]) : 2;

        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup workers = new NioEventLoopGroup(loops);

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(8192))
                                .addLast(new RequestHandler());
                    }
                });

        Channel server = bootstrap.bind("127.0.0.1", port).sync().channel();
        System.out.println("PlainNetty listening on " + port + " workers=" + loops);
        server.closeFuture().sync();
    }

    /** Shared, allocation-free response builder. */
    private static FullHttpResponse response(byte[] body, boolean keepAlive) {
        FullHttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
        res.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(res, body.length);
        if (!keepAlive) {
            res.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }
        return res;
    }

    private static final class RequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            // Malformed request -> fail fast instead of throwing out of the pipeline.
            if (!req.decoderResult().isSuccess()) {
                ctx.writeAndFlush(response(SLOW_BODY, false))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }

            String path = req.uri();
            boolean keepAlive = HttpUtil.isKeepAlive(req);

            if (path.startsWith("/slow")) {
                // Non-blocking 50ms delay, the direct analogue of Mono.delay(50ms):
                // the event loop is released and a timer re-enters later.
                ctx.executor().schedule(
                        () -> {
                            ctx.writeAndFlush(response(SLOW_BODY, keepAlive), ctx.voidPromise());
                            if (!keepAlive) {
                                ctx.close();
                            }
                        },
                        50, TimeUnit.MILLISECONDS);
                return;
            }

            if (path.startsWith("/json") || path.startsWith("/jsonstr")) {
                // voidPromise() = no per-response promise allocation.
                ctx.writeAndFlush(response(JSON, keepAlive), ctx.voidPromise());
                if (!keepAlive) {
                    ctx.close();
                }
                return;
            }

            FullHttpResponse notFound = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, Unpooled.EMPTY_BUFFER);
            HttpUtil.setContentLength(notFound, 0);
            ctx.writeAndFlush(notFound, ctx.voidPromise());
            if (!keepAlive) {
                ctx.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
