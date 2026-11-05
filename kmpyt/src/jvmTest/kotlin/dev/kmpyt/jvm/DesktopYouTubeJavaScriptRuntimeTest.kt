package dev.kmpyt.jvm

import dev.kmpyt.extraction.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*
import io.ktor.http.Url
import org.junit.Assume.assumeTrue
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class DesktopYouTubeJavaScriptRuntimeTest {
    @Test fun compilerDecodesTheUrlHelpersEncodedSignatureBeforeResourceAssembly() {
        val program = """
            var _yt_player={};(function(realm){
            realm.Resource=function(input,enabled){
                var parsed=new URL(input);
                this.set=function(key,value){parsed.searchParams.set(key,value);};
                this.get=function(key){return parsed.searchParams.get(key);};
            };
            var assemble;
            assemble=function(uri,key,challenge){
                uri=new realm.Resource(uri,!0);uri.set("alr","yes");
                if(challenge)uri.set(key,encodeURIComponent(decodeURIComponent(challenge).split("").reverse().join("")));
                return uri;
            };
            })(_yt_player);
        """.trimIndent()
        DesktopYouTubeJavaScriptRuntime().use { runtime ->
            val compilation = ContextYouTubePlayerProgramCompiler(runtime).compile(program, YouTubeProgramBinding())
            val transforms = assertNotNull(compilation.transforms, compilation.diagnostics.joinToString { "${it.code}: ${it.message}" })
            try {
                // Literal percent distinguishes raw signatures from URI-encoded helper inputs/outputs.
                val signature = transforms.signature("a+/=%25")
                assertEquals("52%=/+a", signature)
                val resource = YouTubeUrlAssembly.replaceParameter(
                    "https://media.invalid/videoplayback?itag=136&signed=keep%2fbytes", "sig", signature)
                assertEquals("52%=/+a", Url(resource).parameters["sig"])
                assertTrue(resource.contains("signed=keep%2fbytes"))
                assertFalse(resource.contains("sig=52%2525"))
                assertEquals("fedcba", transforms.signature("abcdef"))
            } finally { transforms.close() }
        }
    }

    @Test fun executesNativeDomAndUrlOperationsInOfflineChromium() {
        DesktopYouTubeJavaScriptRuntime().use { runtime ->
            val session = runtime.prepare("""
                globalThis.__kmpyt_capabilities={signature:true,throttling:true};
                globalThis.__kmpyt_transform=function(kind,input){
                    if(location.origin==='null'||document.cookie!=='')throw new Error('Unexpected offline origin');
                    const node=document.createElement('span');node.textContent=input;
                    const url=new URL('https://invalid.example/videoplayback');
                    url.searchParams.set('result',node.textContent.split('').reverse().join(''));
                    return url.searchParams.get('result');
                };
            """.trimIndent())
            assertTrue(session.capabilities.signature)
            assertEquals("fedcba", session.call(YouTubeJavaScriptFunction.THROTTLING, "abcdef"))
            session.close()
            assertFails { session.call(YouTubeJavaScriptFunction.SIGNATURE, "abc") }
        }
    }

    @Test fun offlinePlayerCannotContactActualLoopbackHttpServer() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/blocked") { exchange -> requests.incrementAndGet(); exchange.sendResponseHeaders(200, 0); exchange.close() }
        server.start()
        try {
            DesktopYouTubeJavaScriptRuntime().use { runtime ->
                val session = runtime.prepare("""
                    globalThis.__kmpyt_capabilities={signature:true,throttling:true};
                    globalThis.__kmpyt_transform=function(kind,input){
                        return fetch('http://127.0.0.1:${server.address.port}/blocked').then(
                            function(){throw new Error('Unexpected network access')},function(){return input+':blocked'});
                    };
                """.trimIndent())
                assertEquals("owned:blocked", session.call(YouTubeJavaScriptFunction.THROTTLING, "owned"))
                assertEquals(0, requests.get())
            }
        } finally { server.stop(0) }
    }

}
