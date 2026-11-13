/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Stub upstream for the end-to-end baseline: answers every request with 200 and a fixed two-byte body, so the
 * gateway in front of it is the bottleneck. Reactor Netty, from the gateway distribution's own classpath:
 * `java -cp "<dist>/lib/*" Stub.java [port]`. (The JDK HttpServer reset the gateway's pooled connections above
 * about 17,000 requests per second.)
 */
public class Stub {
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18081;
        DisposableServer server = HttpServer.create()
            .host("127.0.0.1")
            .port(port)
            .handle((request, response) -> response.header("Content-Type", "text/plain").sendString(Mono.just("ok")))
            .bindNow();
        System.out.println("stub listening on " + port);
        server.onDispose().block();
    }
}
